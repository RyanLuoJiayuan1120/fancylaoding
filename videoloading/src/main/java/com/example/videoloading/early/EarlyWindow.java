package com.example.videoloading.early;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The early window. Created on the MAIN thread during Mixin bootstrap, rendered on a
 * dedicated thread, then handed off to Minecraft via WindowMixin so the game adopts the
 * same GLFW handle (and therefore the same GL context).
 *
 * NeoForge's three rules, which we follow exactly:
 *   1. create the window and all GL objects on the main thread before starting the ticker
 *   2. the render thread must release the context (glfwMakeContextCurrent(0)) before handoff
 *   3. the main thread must make the context current again, or macOS crashes
 */
public final class EarlyWindow {
    private static long handle = 0L;
    private static volatile boolean running = false;
    private static final CountDownLatch released = new CountDownLatch(1);
    private static long aStartNanos;
    private static long lastLogNanos;

    public static boolean hasHandle() { return handle != 0L && VLConfig.adoptWindow; }
    public static long getHandle() { return handle; }

    /** MAIN THREAD. Returns silently if anything at all goes wrong. */
    public static void create() {
        if (!VLConfig.enabled) return;
        try {
            if (!GLFW.glfwInit()) return;

            GLFW.glfwDefaultWindowHints();
            // GLFW context hints only apply at creation time, and Minecraft will not
            // re-apply them because we never let it call glfwCreateWindow. Copy MC's
            // setup exactly: GL 3.2 core, forward-compat on macOS.
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

            handle = GLFW.glfwCreateWindow(VLConfig.windowWidth, VLConfig.windowHeight,
                    "Loading", 0L, 0L);
            if (handle == 0L) {
                System.out.println("[videoloading] glfwCreateWindow returned 0");
                handle = 0L;
                return;
            }

            GLFW.glfwMakeContextCurrent(handle);
            GL.createCapabilities();          // main thread first (macOS)
            EarlyRenderer.init();             // GL objects built on the main thread
            FrameSequence.start();            // background image upload happens here too

            // Release before the render thread takes over — two threads must never
            // hold the same context at once.
            GLFW.glfwMakeContextCurrent(0L);

            aStartNanos = System.nanoTime();
            lastLogNanos = aStartNanos;
            int[] win = getWindowSize(handle);
            int[] fb = getFramebufferSize(handle);
            System.out.println("[videoloading] early window created handle=" + handle
                    + " window=" + win[0] + "x" + win[1]
                    + " framebuffer=" + fb[0] + "x" + fb[1]
                    + " frames=" + FrameSequence.hasFrames()
                    + " renderer=" + EarlyRenderer.isInited());
            running = true;
            Thread t = new Thread(EarlyWindow::loop, "videoloading-render");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            running = false;
            handle = 0L;
            VLConfig.enabled = false;
            System.out.println("[videoloading] early window failed: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** MAIN THREAD only. Called from MixinProbe's transform proxy and during handoff. */
    public static void pollOnMainThread() {
        if (handle != 0L && running) {
            try { GLFW.glfwPollEvents(); } catch (Throwable ignored) {}
        }
    }

    private static void loop() {
        long h = handle;
        if (h == 0L) { released.countDown(); return; }
        try {
            GLFW.glfwMakeContextCurrent(h);
            GL.createCapabilities();
        } catch (Throwable t) {
            running = false;
            released.countDown();
            return;
        }

        long frameNanos = 1_000_000_000L / Math.max(1, VLConfig.fps);
        long last = System.nanoTime();
        try {
            while (running) {
                FrameSequence.pump();

                // MixinProbe is best-effort. On this loader it can be unavailable,
                // which used to leave the bar frozen at 0 forever. Use a time-based
                // fallback and take the larger of the two.
                long now = System.nanoTime();
                float elapsed = (now - aStartNanos) / 1_000_000_000f;
                float timeP = (float) (1.0 - Math.exp(-elapsed / 8.0));
                float mixP = Math.max(MixinProbe.progress(), timeP);
                ProgressBus.setMixin(mixP);

                int[] fb = setViewportToFramebuffer(h);
                String log = LoadingLog.latest();
                if (log == null) log = "Starting Minecraft...";
                EarlyRenderer.uploadLog(log, Math.round(fb[0] * 0.94f));
                String pct = Math.round(ProgressBus.overall() * 100f) + "%";
                EarlyRenderer.draw(ProgressBus.overall(), 1f, fb[0], fb[1], pct);
                GLFW.glfwSwapBuffers(h);

                if (now - lastLogNanos > 1_000_000_000L) {
                    lastLogNanos = now;
                    int[] vp = getGlViewport();
                    System.out.printf(
                            "[videoloading] A t=%.1fs probe=%.2f(installed=%s,count=%d) time=%.2f overall=%.2f "
                                    + "fb=%dx%d glViewport=%dx%d barY=%.2f barW=%.2f%n",
                            elapsed, MixinProbe.progress(), MixinProbe.isInstalled(),
                            MixinProbe.count(), timeP, ProgressBus.overall(), fb[0], fb[1],
                            vp[0], vp[1], VLConfig.barY, VLConfig.barWidth);
                }

                long sleep = frameNanos - (now - last);
                if (sleep > 1_000_000L) {
                    Thread.sleep(sleep / 1_000_000L);
                } else {
                    Thread.sleep(0, 200_000);
                }
                last = System.nanoTime();
            }
        } catch (Throwable t) {
            System.out.println("[videoloading] A render loop failed: " + t);
            t.printStackTrace(System.out);
        } finally {
            try {
                FrameSequence.stop();
                GLFW.glfwMakeContextCurrent(0L);   // rule 2
            } catch (Throwable ignored) {
            }
            released.countDown();
        }
    }

    private static int[] setViewportToFramebuffer(long h) {
        int[] fb = getFramebufferSize(h);
        org.lwjgl.opengl.GL11.glViewport(0, 0, Math.max(1, fb[0]), Math.max(1, fb[1]));
        return fb;
    }

    private static int[] getFramebufferSize(long h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer hb = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(h, w, hb);
            return new int[]{w.get(0), hb.get(0)};
        }
    }

    private static int[] getWindowSize(long h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer hb = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(h, w, hb);
            return new int[]{w.get(0), hb.get(0)};
        }
    }

    private static int[] getGlViewport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(4);
            org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, buf);
            return new int[]{buf.get(2), buf.get(3)};
        }
    }

    /**
     * MAIN THREAD, called from WindowMixin in place of glfwCreateWindow.
     * Waits for the render thread to release the context, optionally holds until the
     * video has played once (bounded by VLConfig.maxHoldMs), then hands the handle over.
     */
    public static long handoff(long maxHoldMs) {
        long h = handle;
        if (h == 0L) return 0L;

        int[] fb = getFramebufferSize(h);
        int[] win = getWindowSize(h);
        System.out.println("[videoloading] handoff begin fb=" + fb[0] + "x" + fb[1]
                + " win=" + win[0] + "x" + win[1]
                + " played=" + FrameSequence.hasPlayedOnce());
        handle = 0L;
        running = false;
        try {
            if (!released.await(3, TimeUnit.SECONDS)) {
                // render thread stuck — proceed anyway, don't hang startup
                System.out.println("[videoloading] handoff: render thread did not release in 3s");
            }
        } catch (InterruptedException ignored) {
        }

        long waitStart = System.nanoTime();
        long deadline = waitStart + Math.max(0L, maxHoldMs) * 1_000_000L;
        while (System.nanoTime() < deadline && !FrameSequence.hasPlayedOnce() && VLConfig.enabled) {
            GLFW.glfwPollEvents();
            try { Thread.sleep(8); } catch (InterruptedException e) { break; }
        }
        System.out.println("[videoloading] handoff: waited " + (System.nanoTime() - waitStart) / 1_000_000L
                + "ms played=" + FrameSequence.hasPlayedOnce());

        // Video over (or we gave up): the still image takes over for the rest of startup.
        EarlyRenderer.useFallbackBackground();

        GLFW.glfwMakeContextCurrent(h);   // rule 3
        try { GL.createCapabilities(); } catch (Throwable ignored) {}
        System.out.println("[videoloading] handoff done, returning handle " + h);
        return h;
    }

    private EarlyWindow() {}
}
