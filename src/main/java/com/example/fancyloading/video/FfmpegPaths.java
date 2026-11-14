package com.example.fancyloading.video;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
        android = detectAndroid();
        return android;
    }

    /**
     * FCL/Pojav run a normal Linux JVM, so android.app.ActivityThread is often
     * not on the classpath at all. Fall back to paths that only Android uses:
     * java.io.tmpdir is /data/user/<id>/<package>/cache/..., while gameDir is
     * under /storage/emulated/<id>/... .
     */
    private static boolean detectAndroid() {
        try {
            Class.forName("android.app.ActivityThread");
            return true;
        } catch (Throwable ignored) {
        }
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (tmp.matches("^/data/(user|data)/\\d+/[^/]+(/.*)?$")) return true;
        String home = System.getProperty("user.home", "");
        if (home.startsWith("/storage/emulated/") || home.startsWith("/sdcard/")) return true;
        return false;
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

    /**
     * Application.getFilesDir() via reflection. If the Android classes are not
     * visible (FCL), derive the app-private root from java.io.tmpdir
     * (/data/user/11/com.tungsten.fcl/cache/fclauncher) and use its files/ dir.
     */
    public static Path androidFilesDir() {
        if (cachedAndroidFiles != null) return cachedAndroidFiles;
        String override = System.getProperty("fancyloading.androidFilesDir");
        if (override != null && !override.isBlank()) {
            cachedAndroidFiles = Path.of(override);
            return cachedAndroidFiles;
        }
        Path fromApi = androidFilesDirViaApi();
        if (fromApi != null) return fromApi;

        String tmp = System.getProperty("java.io.tmpdir", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(/data/(?:user|data)/\\d+/[^/]+)(?:/.*)?$").matcher(tmp);
        if (m.matches()) {
            Path root = Path.of(m.group(1));
            if (Files.isDirectory(root) && Files.isWritable(root)) {
                // files/ may not exist yet; FfmpegProvisioner calls createDirectories.
                cachedAndroidFiles = root.resolve("files");
                return cachedAndroidFiles;
            }
        }
        return null;
    }

    private static Path androidFilesDirViaApi() {
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
