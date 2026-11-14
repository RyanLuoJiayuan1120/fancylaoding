package com.example.fancyloading.video;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android/FCL 上 sdcard 通常挂载为 noexec，chmod +x 也救不了。
 * FFmpeg 必须解压到应用私有目录（/data/user/&lt;id&gt;/&lt;pkg&gt;/files）再执行。
 *
 * <p>国内 ROM 的用户 id 不一定是 0：工作资料、应用分身、手机分身常见
 * 10/11/999 等；FCL 的包名也可能被商店或分身改掉。所以这里不写死任何
 * user id 或包名，而是从 JVM 属性、/proc 里反推当前进程真正的私有目录。</p>
 *
 * <p>该类只用 JDK + 反射，不 import Android 类，桌面端照常回退到 config 目录。</p>
 */
public final class FfmpegPaths {

    /** /data/user/11/com.tungsten.fcl、/data/data/com.tungsten.fcl、/data/user_de/0/... */
    private static final Pattern DATA_ROOT = Pattern.compile(
            "(/data/(?:(?:user|user_de)/\\d+|data)/[^/\\s:]+)");

    private static Boolean android;
    private static Path cachedAndroidFiles;
    private static String cachedSource = "";

    private FfmpegPaths() {
    }

    public static boolean isAndroid() {
        if (android != null) return android;
        android = detectAndroid();
        return android;
    }

    private static boolean detectAndroid() {
        try {
            Class.forName("android.app.ActivityThread");
            return true;
        } catch (Throwable ignored) {
        }
        // FCL/Pojav 的 JVM 里 android.* 往往不可见，只能看路径和环境。
        for (String value : propertyCandidates()) {
            if (extractDataRoot(value) != null) return true;
        }
        if (new File("/system/build.prop").isFile() && new File("/system/bin/app_process").isFile()) {
            return true;
        }
        String androidData = System.getenv("ANDROID_DATA");
        String androidRoot = System.getenv("ANDROID_ROOT");
        return (androidData != null && !androidData.isBlank())
                || (androidRoot != null && !androidRoot.isBlank());
    }

    /**
     * 工具目录：
     * 1. -Dfancyloading.ffmpegDir=... 强制指定
     * 2. Android: 应用私有 files/fancyloading/tools
     * 3. 桌面: &lt;gameDir&gt;/config/fancyloading/tools
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
     * 应用私有 files 目录。顺序：
     * 1. -Dfancyloading.androidFilesDir=...
     * 2. 反射 Context.getFilesDir()（能拿到 Android 类时）
     * 3. 从 java.io.tmpdir / java.home / java.library.path / 环境变量反推
     * 4. /proc/self/status 的 uid + /proc/self/cmdline 的包名反推
     * 5. /proc/self/maps 里出现的 /data/user/... 路径
     */
    public static Path androidFilesDir() {
        if (cachedAndroidFiles != null) return cachedAndroidFiles;

        String override = System.getProperty("fancyloading.androidFilesDir");
        if (override != null && !override.isBlank()) {
            return cache(Path.of(override), "property");
        }

        Path fromApi = androidFilesDirViaApi();
        if (fromApi != null) return cache(fromApi, "Context.getFilesDir");

        for (String value : propertyCandidates()) {
            Path root = extractDataRoot(value);
            if (root != null) {
                Path files = filesUnder(root);
                if (files != null) return cache(files, "property");
            }
        }

        Path fromProc = androidFilesDirFromProc();
        if (fromProc != null) return fromProc;

        Path fromMaps = androidFilesDirFromMaps();
        if (fromMaps != null) return fromMaps;

        return null;
    }

    private static Path cache(Path files, String source) {
        cachedAndroidFiles = files;
        cachedSource = source + " -> " + files;
        return cachedAndroidFiles;
    }

    /** 给 FFmpeg 日志用的简短诊断。 */
    public static String androidDiagnostics() {
        String tmp = System.getProperty("java.io.tmpdir", "");
        String home = System.getProperty("java.home", "");
        return "tmp=" + tmp + ", java.home=" + home
                + (cachedAndroidFiles != null ? ", files=" + cachedAndroidFiles + " [" + cachedSource + "]" : ", files=null");
    }

    private static List<String> propertyCandidates() {
        List<String> out = new ArrayList<>();
        add(out, System.getProperty("java.io.tmpdir"));
        add(out, System.getProperty("java.home"));
        add(out, System.getProperty("java.library.path"));
        add(out, System.getProperty("user.home"));
        add(out, System.getProperty("java.class.path"));
        add(out, System.getenv("TMPDIR"));
        add(out, System.getenv("HOME"));
        add(out, System.getenv("ANDROID_DATA"));
        add(out, System.getenv("ANDROID_ROOT"));
        return out;
    }

    private static void add(List<String> out, String value) {
        if (value != null && !value.isBlank()) out.add(value);
    }

    /** 从任意字符串里抓 /data/user/&lt;id&gt;/&lt;pkg&gt; 形式的应用根目录。 */
    static Path extractDataRoot(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = DATA_ROOT.matcher(text);
        while (m.find()) {
            Path root = Path.of(m.group(1));
            // 排除把 /data/user/11/com.pkg 后面又拼了别的包名的情况：
            // 根目录的最后一节必须像包名（含点），而不是 lib/cache 之类的子目录。
            Path name = root.getFileName();
            if (name != null && name.toString().contains(".")) return root;
        }
        return null;
    }

    /** root/files；root 存在但 files 还没建也返回（后面会 mkdirs）。 */
    private static Path filesUnder(Path root) {
        try {
            if (!Files.isDirectory(root) || !Files.isWritable(root)) return null;
            Path files = root.resolve("files");
            if (Files.isDirectory(files)) {
                return Files.isWritable(files) ? files : null;
            }
            return files; // createDirectories 会建出来
        } catch (Exception e) {
            return null;
        }
    }

    private static Path androidFilesDirFromProc() {
        String pkg = processPackage();
        int uid = processUid();
        if (pkg == null || uid < 0) return null;
        int userId = uid / 100000;
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("/data/user", Integer.toString(userId), pkg));
        candidates.add(Path.of("/data/user_de", Integer.toString(userId), pkg));
        candidates.add(Path.of("/data/data", pkg));
        if (userId != 0) candidates.add(Path.of("/data/user/0", pkg));
        for (Path root : candidates) {
            Path files = filesUnder(root);
            if (files != null) return cache(files, "proc uid=" + uid + " pkg=" + pkg);
        }
        return null;
    }

    private static Path androidFilesDirFromMaps() {
        try {
            Path maps = Path.of("/proc/self/maps");
            if (!Files.isRegularFile(maps)) return null;
            byte[] bytes = Files.readAllBytes(maps);
            String text = new String(bytes, StandardCharsets.UTF_8);
            Matcher m = DATA_ROOT.matcher(text);
            while (m.find()) {
                Path root = Path.of(m.group(1));
                Path files = filesUnder(root);
                if (files != null) return cache(files, "maps");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String processPackage() {
        try {
            byte[] bytes = Files.readAllBytes(Path.of("/proc/self/cmdline"));
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) end++;
            String name = new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (name.isEmpty() || name.contains("/") || name.equals("app_process")) return null;
            return name.contains(".") ? name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int processUid() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (!line.startsWith("Uid:")) continue;
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length == 0) return -1;
                return Integer.parseInt(parts[0]);
            }
        } catch (Throwable ignored) {
        }
        return -1;
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
                return file.toPath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
