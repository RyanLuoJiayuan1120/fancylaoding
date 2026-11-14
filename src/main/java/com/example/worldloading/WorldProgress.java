package com.example.worldloading;

/** Static progress bus: survives the very short per-screen lifetimes. */
public final class WorldProgress {

    public enum Kind {
        NONE, GENERATING
    }

    private static final long SESSION_GAP_NANOS = 5_000_000_000L;

    private static float value;
    private static boolean complete;
    private static long sessionStartNanos;
    private static long lastEndNanos;
    private static Kind lastKind = Kind.NONE;
    private static long sessionId;

    private WorldProgress() {
    }

    public static void begin(Kind kind) {
        long now = System.nanoTime();
        if (lastKind == Kind.NONE || now - lastEndNanos > SESSION_GAP_NANOS) {
            value = 0f;
            complete = false;
            sessionStartNanos = now;
            sessionId++;
        }
        lastKind = kind;
    }

    /** Monotonic: a later screen never makes the bar jump backwards. */
    public static void set(float progress) {
        float p = WLConfig.clamp(progress, 0f, 1f);
        if (p > value) {
            value = p;
        }
    }

    public static void setRealPercent(int percent) {
        set(percent / 100f);
    }

    /** Time-saturated estimate; caller must still route it through set(). */
    public static float estimate(float kSeconds, float cap) {
        float t = (System.nanoTime() - sessionStartNanos) / 1_000_000_000f;
        return Math.min(cap, (float) (1.0 - Math.exp(-t / Math.max(0.1f, kSeconds))));
    }

    public static void complete() {
        value = 1f;
        complete = true;
    }

    public static void end() {
        lastEndNanos = System.nanoTime();
        lastKind = Kind.NONE;
    }

    public static float get() {
        return value;
    }

    public static boolean isComplete() {
        return complete;
    }

    public static long sessionId() {
        return sessionId;
    }

    public static long sessionStartNanos() {
        return sessionStartNanos;
    }
}
