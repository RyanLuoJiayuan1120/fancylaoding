package com.example.videoloading.early;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dead-simple "video": a directory of PNG frames played back at VLConfig.fps.
 * Swap this for JCodec / JavaCPP-FFmpeg if you want real MP4 — the interface
 * (decode off-thread, hand a ByteBuffer to the render thread) stays the same.
 *
 * Decoding runs on its own thread; only the upload happens on the GL thread.
 */
public final class FrameSequence {
    private static final List<Path> frames = new ArrayList<>();
    private static volatile ByteBuffer pending;
    private static volatile int pendingW, pendingH;
    private static volatile boolean playedOnce = false;
    private static volatile boolean running = false;
    private static Thread decoder;

    public static boolean hasPlayedOnce() { return playedOnce; }
    public static boolean hasFrames() { return !frames.isEmpty(); }

    public static void scan() {
        frames.clear();
        try {
            Path dir = VLConfig.resolve(VLConfig.framesDir);
            if (!Files.isDirectory(dir)) return;
            Files.list(dir)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .sorted()
                    .forEach(frames::add);
        } catch (IOException ignored) {
        }
    }

    /** Still image used as the background once the video ends (or if there is no video). */
    public static void loadBackground() {
        Path p = VLConfig.resolve(VLConfig.background);
        if (!Files.isRegularFile(p)) return;
        ByteBuffer img = decode(p);
        if (img != null) {
            int w = lastW, h = lastH;
            EarlyRenderer.upload(EarlyRenderer.TEX_BG, img, w, h);
            STBImage.stbi_image_free(img);
        }
    }

    public static void start() {
        scan();
        loadBackground();
        if (frames.isEmpty()) {
            playedOnce = true;   // nothing to wait for
            return;
        }
        running = true;
        decoder = new Thread(FrameSequence::decodeLoop, "videoloading-decode");
        decoder.setDaemon(true);
        decoder.start();
    }

    /** Called from the GL thread: upload whatever the decoder has produced. */
    public static void pump() {
        ByteBuffer buf = pending;
        if (buf == null) return;
        int w = pendingW, h = pendingH;
        pending = null;
        EarlyRenderer.upload(EarlyRenderer.TEX_FRAME, buf, w, h);
        STBImage.stbi_image_free(buf);
    }

    public static void stop() { running = false; }

    private static void decodeLoop() {
        int i = 0;
        long frameNanos = 1_000_000_000L / Math.max(1, VLConfig.fps);
        while (running) {
            long frameStart = System.nanoTime();
            ByteBuffer img = decode(frames.get(i));
            if (img != null) {
                // wait for the render thread to consume the previous frame
                while (pending != null && running) {
                    try { Thread.sleep(2); } catch (InterruptedException e) { return; }
                }
                pendingW = lastW;
                pendingH = lastH;
                pending = img;
            }
            i++;
            if (i >= frames.size()) {
                playedOnce = true;
                if (VLConfig.enabled) {   // loop until handoff, the render thread decides when to stop
                    i = 0;
                } else {
                    break;
                }
            }
            // Pace the decoder to the configured fps. Without this it decodes the
            // whole sequence in well under a second and the "video" is over before
            // the window is handed off.
            long sleep = frameNanos - (System.nanoTime() - frameStart);
            if (sleep > 1_000_000L) {
                try { Thread.sleep(sleep / 1_000_000L); } catch (InterruptedException e) { return; }
            } else {
                Thread.yield();
            }
        }
    }

    private static int lastW, lastH;

    private static ByteBuffer decode(Path path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);
            File f = path.toFile();
            ByteBuffer img = STBImage.stbi_load(f.getAbsolutePath(), w, h, c, 4);
            if (img == null) return null;
            lastW = w.get(0);
            lastH = h.get(0);
            return img;
        }
    }

    private FrameSequence() {}
}
