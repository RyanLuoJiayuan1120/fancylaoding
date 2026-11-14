package com.example.fancyloading.video;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Android/FCL 上 sdcard 通常挂载为 noexec，chmod +x 也救不了。
 * FFmpeg 必须解压到应用私有 filesDir（/data/user/0/<pkg>/files）再执行。
 *
 * 该类只用 JDK + 反射，不 import Android 类，桌面端照常回退到 config 目录。
 */
public final class FfmpegPaths {

    private static Boolean android;
    private static Path cachedAndroidFiles;

    private FfmpegPaths() {
    }

    public static boolean isAndroid() {
        if (android != null) return android;
        try {
            Class.forName("android.app.ActivityThread");
            android = true;
        } catch (Throwable t) {
            android = false;
        }
        return android;
    }

    /**
     * 工具目录：
     * 1. -Dfancyloading.ffmpegDir=... 强制指定
     * 2. Android: Context.getFilesDir()/fancyloading/tools
     * 3. 桌面: <gameDir>/config/fancyloading/tools
     */
    public static Path toolRoot(Path gameDir) {
        String override = System.getProperty("fancyloading.ffmpegDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (isAndroid()) {
            Path files = androidFilesDir();
            if (files != null) {
                return files.resolve("fancyloading").resolve("tools");
            }
        }
        return gameDir.resolve("config").resolve("fancyloading").resolve("tools");
    }

    /** Application.getFilesDir() via reflection; null if FCL's runtime doesn't expose it. */
    public static Path androidFilesDir() {
        if (cachedAndroidFiles != null) return cachedAndroidFiles;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = null;
            try {
                Method currentApplication = activityThread.getMethod("currentApplication");
                app = currentApplication.invoke(null);
            } catch (Throwable ignored) {
            }
            if (app == null) {
                try {
                    Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
                    if (thread != null) {
                        app = thread.getClass().getMethod("getApplication").invoke(thread);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (app == null) return null;
            Object files = app.getClass().getMethod("getFilesDir").invoke(app);
            if (files instanceof File file) {
                cachedAndroidFiles = file.toPath();
                return cachedAndroidFiles;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
