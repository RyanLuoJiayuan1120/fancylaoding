package com.example.videoloading.early;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Config loaded with plain java.util.Properties — deliberately NOT Gson and NOT any
 * Minecraft class, because this is touched from the Mixin plugin (onLoad), long before
 * Minecraft classes may be loaded.
 *
 * File: <gameDir>/config/videoloading.properties
 */
public final class VLConfig {
    public static volatile boolean enabled      = true;
    public static volatile boolean adoptWindow  = true;   // hand the early window over to the game
    public static volatile int     windowWidth  = 854;    // MC's default windowed size — keep in sync!
    public static volatile int     windowHeight = 480;
    public static volatile String  framesDir    = "config/videoloading/frames";
    public static volatile String  background   = "config/videoloading/background.png";
    public static volatile String  fontPath     = "config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf";
    public static volatile int     fps          = 24;
    public static volatile long    maxHoldMs    = 3000;   // upper bound on "wait for the video to end"
    public static volatile float   mixinShare   = 0.8f;   // mixin 0..80, resources 80..100
    public static volatile int     barColor     = 0xFFFFFFFF;
    public static volatile int     barBgColor   = 0xAA000000;
    public static volatile float   barWidth     = 1.0f;   // ratio of viewport width
    public static volatile float   barHeight    = 6f;     // pixels
    public static volatile float   barY         = 0.84f;  // ratio of viewport height
    /** Set to false by VideoLoadingClient when Drippy Loading Screen is present. */
    public static volatile boolean takeOverSplash = true;

    public static Path gameDir() {
        return Paths.get(System.getProperty("user.dir", "."));
    }

    public static Path resolve(String p) {
        Path path = Paths.get(p);
        return path.isAbsolute() ? path : gameDir().resolve(p);
    }

    public static void load() {
        Path file = gameDir().resolve("config").resolve("videoloading.properties");
        if (!Files.isRegularFile(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        enabled      = bool(p, "enabled", enabled);
        adoptWindow  = bool(p, "adoptWindow", adoptWindow);
        windowWidth  = intg(p, "windowWidth", windowWidth);
        windowHeight = intg(p, "windowHeight", windowHeight);
        framesDir    = p.getProperty("framesDir", framesDir);
        background   = p.getProperty("background", background);
        fontPath     = p.getProperty("fontPath", fontPath);
        fps          = intg(p, "fps", fps);
        maxHoldMs    = intg(p, "maxHoldMs", (int) maxHoldMs);
        mixinShare   = flt(p, "mixinShare", mixinShare);
        barColor     = hex(p, "barColor", barColor);
        barBgColor   = hex(p, "barBgColor", barBgColor);
        barWidth     = flt(p, "barWidth", barWidth);
        barHeight    = flt(p, "barHeight", barHeight);
        barY         = flt(p, "barY", barY);
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int intg(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k).trim()); } catch (Exception e) { return def; }
    }

    private static float flt(Properties p, String k, float def) {
        try { return Float.parseFloat(p.getProperty(k).trim()); } catch (Exception e) { return def; }
    }

    private static int hex(Properties p, String k, int def) {
        try { return (int) Long.parseLong(p.getProperty(k).trim(), 16); } catch (Exception e) { return def; }
    }

    private VLConfig() {}
}
