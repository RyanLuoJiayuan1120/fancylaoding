package com.example.worldloading;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loaded from config/worldloading.properties; v2 defaults. */
public final class WLConfig {

    public static boolean enabled = true;
    public static float barWidth = 1.0f;
    public static int barHeight = 6;
    public static float barY = 0.84f;
    public static float textY = 0.78f;
    public static float textPadding = 0.03f;
    public static boolean showText = true;
    public static int fontSize = 14;
    /** Font stays outside the jar; path is relative to the game dir. */
    public static String fontPath = "config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf";
    public static float estimateK = 7.0f;
    public static float estimateCap = 0.95f;
    public static int barColor = 0xFFFFFFFF;
    public static int barBgColor = 0xAA000000;

    public static boolean videoEnabled = true;
    public static String videoPath = "config/worldloading/video.mp4";
    public static int videoFps = 15;
    public static int videoFrames = 75;
    public static int videoWidth = 480;
    public static int videoHeight = 270;
    public static float videoDim = 0.18f;

    private WLConfig() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("worldloading.properties");
        Properties p = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException ignored) {
                // keep defaults
            }
        }
        enabled = bool(p, "enabled", enabled);
        barWidth = clamp((float) dbl(p, "barWidth", barWidth), 0.05f, 1.0f);
        barHeight = Math.max(1, integer(p, "barHeight", barHeight));
        barY = clamp((float) dbl(p, "barY", barY), 0.75f, 0.98f);
        textY = clamp((float) dbl(p, "textY", textY), 0.75f, barY - 0.01f);
        textPadding = clamp((float) dbl(p, "textPadding", textPadding), 0.0f, 0.2f);
        showText = bool(p, "showText", showText);
        fontSize = Math.max(8, integer(p, "fontSize", fontSize));
        fontPath = p.getProperty("fontPath", fontPath).trim();
        estimateK = Math.max(1.0f, (float) dbl(p, "estimateK", estimateK));
        estimateCap = clamp((float) dbl(p, "estimateCap", estimateCap), 0.1f, 0.95f);
        barColor = color(p, "barColor", barColor);
        barBgColor = color(p, "barBgColor", barBgColor);

        videoEnabled = bool(p, "videoEnabled", videoEnabled);
        videoPath = p.getProperty("videoPath", videoPath);
        videoFps = Math.max(1, integer(p, "videoFps", videoFps));
        videoFrames = Math.max(1, integer(p, "videoFrames", videoFrames));
        videoWidth = Math.max(1, integer(p, "videoWidth", videoWidth));
        videoHeight = Math.max(1, integer(p, "videoHeight", videoHeight));
        videoDim = clamp((float) dbl(p, "videoDim", videoDim), 0.0f, 0.9f);

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                try (OutputStream out = Files.newOutputStream(path)) {
                    p.store(out, "worldloading v2 defaults");
                }
            } catch (IOException ignored) {
                // not fatal
            }
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double dbl(Properties p, String key, double fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int color(Properties p, String key, int fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : (int) Long.parseLong(v.trim(), 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
