package com.example.fancyloading.video;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Pure-Java H.264/MP4 decoder for Android (and as a desktop fallback).
 *
 * <p>Android 10+ refuses to execve binaries from app-private storage, so the
 * FFmpeg route can never work on FCL/Pojav. JCodec needs no native binary: it
 * decodes H.264 in the JVM and this class converts the YUV420 planes straight
 * into the RGBA frames the loading renderers already expect.</p>
 *
 * <p>JCodec stores planes as signed bytes offset by -128
 * ({@code stored = value - 128}), so every sample must add 128 back before
 * applying the BT.709 limited-range matrix.</p>
 *
 * <p>For 4K sources JCodec is slow (hundreds of ms per frame). The bundle
 * therefore ships a 960x540/15fps {@code video-mobile.mp4} next to the 4K
 * master; callers should prefer the mobile file when it exists.</p>
 */
public final class JCodecVideoSource implements VideoSource {

    private static final int[] Y_LUT = new int[256];
    private static final int[] C_LUT = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int y = Math.max(0, Math.min(255, (i - 16) * 255 / 219));
            Y_LUT[i] = y << 8;
            C_LUT[i] = (i - 128) * 255 / 224;
        }
    }

    private final Path video;
    private final int width;
    private final int height;
    private final int fps;
    private final Consumer<String> log;
    private final Thread reader;
    private final Object frameLock = new Object();

    private volatile SeekableByteChannel channel;
    private volatile ByteBuffer latest;
    private volatile boolean running = true;
    private volatile boolean firstFrame;
    private volatile String error;

    private JCodecVideoSource(Path video, int width, int height, int fps, Consumer<String> log) {
        this.video = video;
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.log = log;
        this.reader = new Thread(this::readLoop, "fancyloading-jcodec");
        this.reader.setDaemon(true);
    }

    public static JCodecVideoSource start(Path video, int width, int height, int fps,
                                          Consumer<String> log) {
        JCodecVideoSource source = new JCodecVideoSource(video, width, height, fps, log);
        source.reader.start();
        return source;
    }

    private void readLoop() {
        long frameInterval = 1_000_000_000L / fps;
        long next = System.nanoTime();
        try {
            while (running) {
                if (channel == null) {
                    open();
                }
                Picture picture = grab.getNativeFrame();
                if (picture == null) {
                    // Rewind the demuxer/decoder instead of reopening: FrameGrab
                    // owns an H264Decoder with a daemon thread pool, and creating
                    // a new one every loop would leak threads and buffers.
                    grab = grab.seekToFramePrecise(0);
                    next = System.nanoTime();
                    continue;
                }
                ByteBuffer frame = convert(picture);
                synchronized (frameLock) {
                    latest = frame;
                }
                firstFrame = true;

                next += frameInterval;
                long now = System.nanoTime();
                if (next < now - frameInterval * 2) {
                    next = now; // decoding fell behind; do not burst
                }
                long sleep = next - now;
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                }
            }
        } catch (InterruptedException ignored) {
            // close()
        } catch (Throwable t) {
            if (running) {
                error = t.toString();
                if (log != null) log.accept("JCodec video stopped: " + t);
            }
        } finally {
            running = false;
            closeChannel();
        }
    }

    private FrameGrab grab;

    private void open() throws Exception {
        channel = NIOUtils.readableChannel(video.toFile());
        grab = FrameGrab.createFrameGrab(channel);
    }

    private void closeChannel() {
        SeekableByteChannel ch = channel;
        channel = null;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ByteBuffer convert(Picture picture) {
        ColorSpace color = picture.getColor();
        if (picture.isHiBD() || (color != ColorSpace.YUV420 && color != ColorSpace.YUV420J)) {
            throw new UnsupportedOperationException("unsupported JCodec color space: " + color);
        }
        boolean limited = color != ColorSpace.YUV420J;

        int srcW = picture.getCroppedWidth();
        int srcH = picture.getCroppedHeight();
        if (srcW <= 1 || srcH <= 1) {
            throw new IllegalStateException("bad video size " + srcW + "x" + srcH);
        }
        int ox = picture.getCrop() == null ? 0 : picture.getCrop().getX();
        int oy = picture.getCrop() == null ? 0 : picture.getCrop().getY();

        byte[] yPlane = picture.getPlaneData(0);
        byte[] uPlane = picture.getPlaneData(1);
        byte[] vPlane = picture.getPlaneData(2);
        int yStride = picture.getPlaneWidth(0);
        int cStride = picture.getPlaneWidth(1);
        int cHeight = picture.getPlaneHeight(1);

        // COVER: scale up until both axes cover, then centre-crop.
        double scale = Math.max(width / (double) srcW, height / (double) srcH);
        double cropW = width / scale;
        double cropH = height / scale;
        double sx0 = (srcW - cropW) / 2.0;
        double sy0 = (srcH - cropH) / 2.0;

        int[] x0 = new int[width];
        int[] x1 = new int[width];
        int[] cx0 = new int[width];
        int[] cx1 = new int[width];
        float[] fx = new float[width];
        float[] cfx = new float[width];
        for (int x = 0; x < width; x++) {
            double sx = clampCoord(sx0 + (x + 0.5) / scale, srcW);
            int ix = (int) Math.floor(sx);
            float f = (float) (sx - ix);
            x0[x] = ix;
            x1[x] = ix + 1;
            fx[x] = f;

            double cx = clampCoord((ox + sx) * 0.5, cStride);
            int cix = (int) Math.floor(cx);
            cfx[x] = (float) (cx - cix);
            cx0[x] = cix;
            cx1[x] = Math.min(cStride - 1, cix + 1);
        }

        int[] cy0 = new int[height];
        int[] cy1 = new int[height];
        float[] cfy = new float[height];
        for (int y = 0; y < height; y++) {
            double sy = clampCoord(sy0 + (y + 0.5) / scale, srcH);
            double cy = clampCoord((oy + sy) * 0.5, cHeight);
            int ciy = (int) Math.floor(cy);
            cfy[y] = (float) (cy - ciy);
            cy0[y] = ciy;
            cy1[y] = Math.min(cHeight - 1, ciy + 1);
        }

        if (srcW == width && srcH == height) {
            return convertExact(yPlane, uPlane, vPlane, yStride, cStride, ox, oy, limited);
        }

        ByteBuffer out = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            double sy = clampCoord(sy0 + (y + 0.5) / scale, srcH);
            int iy = (int) Math.floor(sy);
            float fy = (float) (sy - iy);
            int row0 = (oy + iy) * yStride + ox;
            int row1 = row0 + yStride;
            int cRow0 = cy0[y] * cStride;
            int cRow1 = cy1[y] * cStride;

            for (int x = 0; x < width; x++) {
                int x0i = x0[x];
                int x1i = x1[x];
                int yv = bilerp(yPlane[row0 + x0i], yPlane[row0 + x1i],
                        yPlane[row1 + x0i], yPlane[row1 + x1i], fx[x], fy);

                int c0 = cx0[x];
                int c1 = cx1[x];
                int uu = bilerp(uPlane[cRow0 + c0], uPlane[cRow0 + c1],
                        uPlane[cRow1 + c0], uPlane[cRow1 + c1], cfx[x], cfy[y]);
                int vv = bilerp(vPlane[cRow0 + c0], vPlane[cRow0 + c1],
                        vPlane[cRow1 + c0], vPlane[cRow1 + c1], cfx[x], cfy[y]);

                if (limited) {
                    yv = Y_LUT[yv];
                } else {
                    yv <<= 8;
                }
                int u = limited ? C_LUT[uu] : (uu - 128);
                int v = limited ? C_LUT[vv] : (vv - 128);

                int r = (yv + 403 * v) >> 8;
                int g = (yv - 48 * u - 120 * v) >> 8;
                int b = (yv + 475 * u) >> 8;

                out.put((byte) clamp(r, 0, 255));
                out.put((byte) clamp(g, 0, 255));
                out.put((byte) clamp(b, 0, 255));
                out.put((byte) 255);
            }
        }
        out.flip();
        return out;
    }

    /** No scaling: integer sampling is several times faster than bilinear. */
    private ByteBuffer convertExact(byte[] yPlane, byte[] uPlane, byte[] vPlane,
                                    int yStride, int cStride, int ox, int oy, boolean limited) {
        byte[] out = new byte[width * height * 4];
        if (width * height >= 1_000_000 && Runtime.getRuntime().availableProcessors() > 1) {
            final boolean lim = limited;
            java.util.stream.IntStream.range(0, height).parallel()
                    .forEach(y -> convertRowExact(yPlane, uPlane, vPlane, yStride, cStride,
                            ox, oy, lim, y, out));
        } else {
            for (int y = 0; y < height; y++) {
                convertRowExact(yPlane, uPlane, vPlane, yStride, cStride, ox, oy, limited, y, out);
            }
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(out.length);
        direct.put(out);
        direct.flip();
        return direct;
    }

    private void convertRowExact(byte[] yPlane, byte[] uPlane, byte[] vPlane,
                                 int yStride, int cStride, int ox, int oy,
                                 boolean limited, int y, byte[] out) {
        int yRow = (oy + y) * yStride + ox;
        int cRow = ((oy + y) >> 1) * cStride + (ox >> 1);
        int p = y * width * 4;
        for (int x = 0; x < width; x++) {
            int yy = (yPlane[yRow + x] + 128) & 0xff;
            int uu = (uPlane[cRow + (x >> 1)] + 128) & 0xff;
            int vv = (vPlane[cRow + (x >> 1)] + 128) & 0xff;
            int yScaled = limited ? Y_LUT[yy] : yy << 8;
            int u = limited ? C_LUT[uu] : uu - 128;
            int v = limited ? C_LUT[vv] : vv - 128;
            out[p++] = (byte) clamp((yScaled + 403 * v) >> 8, 0, 255);
            out[p++] = (byte) clamp((yScaled - 48 * u - 120 * v) >> 8, 0, 255);
            out[p++] = (byte) clamp((yScaled + 475 * u) >> 8, 0, 255);
            out[p++] = (byte) 255;
        }
    }

    /** JCodec plane bytes are {@code value - 128}; bilinear in that signed domain. */
    private static int bilerp(byte a, byte b, byte c, byte d, float fx, float fy) {
        int ai = (a + 128) & 0xff;
        int bi = (b + 128) & 0xff;
        int ci = (c + 128) & 0xff;
        int di = (d + 128) & 0xff;
        float top = ai + (bi - ai) * fx;
        float bottom = ci + (di - ci) * fx;
        int v = Math.round(top + (bottom - top) * fy);
        return v < 0 ? 0 : (Math.min(v, 255));
    }

    /** Keep bilinear samples inside the plane; avoids reading past the last row. */
    private static double clampCoord(double v, int size) {
        if (v < 0) return 0;
        double max = size - 1.001;
        return Math.min(v, max);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (Math.min(v, max));
    }

    @Override
    public ByteBuffer takeFrame() {
        synchronized (frameLock) {
            ByteBuffer frame = latest;
            latest = null;
            return frame;
        }
    }

    @Override
    public boolean hasFirstFrame() {
        return firstFrame;
    }

    @Override
    public String error() {
        return error;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void close() {
        running = false;
        reader.interrupt();
        closeChannel();
    }
}
