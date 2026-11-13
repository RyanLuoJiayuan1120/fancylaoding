package com.example.videoloading.early;

/** OS detection. Pure JDK — safe to touch from the very early bootstrap. */
public final class Platform {
    public static final boolean WINDOWS;
    public static final boolean MACOS;
    public static final boolean LINUX;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        WINDOWS = os.contains("win");
        MACOS = os.contains("mac");
        LINUX = !WINDOWS && !MACOS;
    }

    private Platform() {}
}
