package com.example.videoloading.early;

/**
 * Single source of truth for the progress bar. Read from the render thread,
 * written from the main thread — hence volatile.
 */
public final class ProgressBus {
    private static volatile float mixinP = 0f;
    private static volatile float resP   = 0f;
    private static volatile boolean mixinDone = false;
    /** true once the very first (startup) resource reload has completed. */
    private static volatile boolean startupDone = false;

    /** 0..1 for the bar. Startup: mixin 0-80 + resources 80-100. Later reloads: plain 0-100. */
    public static float overall() {
        if (startupDone) return resP;
        float share = VLConfig.mixinShare;
        return share * mixinP + (1f - share) * resP;
    }

    public static void setMixin(float p)      { mixinP = clamp(p); }
    public static void finishMixin()          { mixinP = 1f; mixinDone = true; }
    public static boolean isMixinDone()       { return mixinDone; }
    public static void setResource(float p)   { resP = clamp(p); }
    public static void markStartupDone()      { startupDone = true; }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private ProgressBus() {}
}
