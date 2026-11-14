package com.example.fancyloading.video;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Streams raw RGBA frames from a bundled/provisioned FFmpeg process.
 *
 * <p>All decoding happens off-thread. Only the latest frame is kept; slow
 * renderers drop frames instead of building a queue.</p>
 */
public final class FfmpegVideoSource implements AutoCloseable {

    private final Process process;
    private final int width;
    private final int height;
    private final int frameSize;
    private final Thread reader;
    private final Object frameLock = new Object();

    private volatile ByteBuffer latest;
    private volatile boolean running = true;
    private volatile boolean firstFrame;
    private volatile String error;

    private FfmpegVideoSource(Process process, int width, int height) {
        this.process = process;
        this.width = width;
        this.height = height;
        this.frameSize = width * height * 4;
        this.reader = new Thread(this::readLoop, "fancyloading-video");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static FfmpegVideoSource start(Path ffmpeg, Path video, int width, int height, int fps)
            throws IOException {
        String filter = "fps=" + Math.max(1, fps)
                + ",scale=" + width + ":" + height
                + ":force_original_aspect_ratio=increase,"
                + "crop=" + width + ":" + height;
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.toString(),
                "-hide_banner", "-loglevel", "error",
                "-stream_loop", "-1",
                "-i", video.toString(),
                "-an",
                "-vf", filter,
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "-"
        );
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        return new FfmpegVideoSource(process, width, height);
    }

    private void readLoop() {
        byte[] frame = new byte[frameSize];
        try (InputStream in = process.getInputStream()) {
            while (running) {
                if (!readFully(in, frame)) break;
                ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
                buffer.put(frame);
                buffer.flip();
                synchronized (frameLock) {
                    latest = buffer;
                }
                firstFrame = true;
            }
        } catch (Throwable t) {
            error = t.getMessage();
        } finally {
            running = false;
        }
    }

    private static boolean readFully(InputStream in, byte[] out) throws IOException {
        int offset = 0;
        while (offset < out.length) {
            int n = in.read(out, offset, out.length - offset);
            if (n < 0) return false;
            offset += n;
        }
        return true;
    }

    /** Latest frame, or null if the renderer already consumed it. */
    public ByteBuffer takeFrame() {
        synchronized (frameLock) {
            ByteBuffer frame = latest;
            latest = null;
            return frame;
        }
    }

    public boolean hasFirstFrame() {
        return firstFrame;
    }

    public String error() {
        return error;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    public void close() {
        running = false;
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        reader.interrupt();
    }
}
