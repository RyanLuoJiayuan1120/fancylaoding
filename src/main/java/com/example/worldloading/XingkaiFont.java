package com.example.worldloading;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders the loading label with the bundled-in-config LXGW WenKai Lite font.
 *
 * <p>The font is deliberately NOT shipped in the jar. It is loaded from
 * {@code config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf} (configurable). If the file is
 * missing we simply draw no text, as the v2 spec requires: the bar layout must
 * not depend on font availability.</p>
 *
 * <p>Implementation note: instead of hooking into Minecraft's font atlas we
 * rasterise the short label with AWT into a small fixed-size texture and submit
 * it through {@link GuiGraphics#blit}. This keeps the 1.21.11 GUI render-state
 * pipeline intact and works with any TTF, including CJK.</p>
 */
public final class XingkaiFont {

    private static final int ATLAS_W = 512;
    private static final int ATLAS_H = 64;
    private static final Identifier TEXTURE_ID =
            Identifier.fromNamespaceAndPath("worldloading", "font/loading_text");

    private static Font font;
    private static boolean fontTried;
    private static DynamicTexture texture;

    private static String cachedText;
    private static float cachedAlpha = -1f;
    private static int cachedScale = -1;
    private static int contentX;
    private static int contentY;
    private static int contentW;
    private static int contentH;

    private XingkaiFont() {
    }

    /** Called once from the client initializer; safe to call repeatedly. */
    public static void init() {
        ensureFont();
        if (font != null) {
            WorldLoadingClient.LOGGER.info("worldloading: LXGW WenKai loaded from {}", fontPath());
        } else {
            WorldLoadingClient.LOGGER.warn("worldloading: LXGW WenKai not found at {}; text disabled",
                    fontPath());
        }
    }

    public static boolean isReady() {
        ensureFont();
        return font != null;
    }

    public static void draw(GuiGraphics ctx, String text, float y, float padding,
                            int sizePx, float alpha) {
        if (!isReady() || text == null || text.isEmpty()) {
            return;
        }
        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        if (texture == null) {
            texture = new DynamicTexture("worldloading-loading-text", ATLAS_W, ATLAS_H, true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        }
        if (!text.equals(cachedText) || Math.abs(alpha - cachedAlpha) > 0.01f
                || guiScale != cachedScale) {
            rebuild(text, sizePx, alpha, guiScale);
        }
        if (contentW <= 0 || contentH <= 0) {
            return;
        }

        // contentW/H are texture pixels; divide by GUI scale to get GUI units.
        int drawW = Math.max(1, Math.round(contentW / (float) guiScale));
        int drawH = Math.max(1, Math.round(contentH / (float) guiScale));
        float pad = WLConfig.clamp(padding, 0f, 0.2f);
        int maxW = Math.max(1, Math.round(w * (1f - 2f * pad)));
        if (drawW > maxW) {
            float fit = maxW / (float) drawW;
            drawW = maxW;
            drawH = Math.max(1, Math.round(drawH * fit));
        }

        // User preference: left align the percentage with the same 3% gutter
        // used by the layout, instead of centring it.
        int cy = Math.round(h * WLConfig.clamp(y, 0f, 1f));
        int x = Math.round(w * pad);
        int top = cy - drawH / 2;

        float u0 = contentX / (float) ATLAS_W;
        float u1 = (contentX + contentW) / (float) ATLAS_W;
        float v0 = contentY / (float) ATLAS_H;
        float v1 = (contentY + contentH) / (float) ATLAS_H;
        ctx.blit(TEXTURE_ID, x, top, x + drawW, top + drawH, u0, u1, v0, v1);
    }

    private static void rebuild(String text, int sizePx, float alpha, int guiScale) {
        // sizePx is in PHYSICAL pixels (A-segment reference). Render the glyphs at
        // that size and let drawW/H divide by guiScale below.
        int renderSize = Math.max(8, sizePx);
        BufferedImage image = new BufferedImage(ATLAS_W, ATLAS_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setFont(font.deriveFont((float) renderSize));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            int th = fm.getAscent() + fm.getDescent();
            int maxTextWidth = ATLAS_W - 8;
            if (tw > maxTextWidth) {
                float fit = maxTextWidth / (float) tw;
                renderSize = Math.max(8, Math.round(renderSize * fit));
                g.setFont(font.deriveFont((float) renderSize));
                fm = g.getFontMetrics();
                tw = fm.stringWidth(text);
                th = fm.getAscent() + fm.getDescent();
            }

            int tx = Math.max(0, (ATLAS_W - tw) / 2);
            int ty = Math.max(0, (ATLAS_H - th) / 2);
            int baseline = ty + fm.getAscent();
            int a = Math.round(255f * WLConfig.clamp(alpha, 0f, 1f));

            // The video frame is often light, so a plain white glyph disappears.
            // Draw a dark 8-direction outline first, then the white glyph on top.
            int outline = Math.max(2, guiScale);
            g.setColor(new Color(0, 0, 0, Math.round(200f * (a / 255f))));
            for (int oy = -outline; oy <= outline; oy += outline) {
                for (int ox = -outline; ox <= outline; ox += outline) {
                    if (ox != 0 || oy != 0) {
                        g.drawString(text, tx + ox, baseline + oy);
                    }
                }
            }
            g.setColor(new Color(255, 255, 255, a));
            g.drawString(text, tx, baseline);

            // Keep a transparent gutter wide enough for the outline plus linear
            // filtering, otherwise the antialiased edge gets clipped.
            int gutter = outline + 2;
            contentX = Math.max(0, tx - gutter);
            contentY = Math.max(0, ty - gutter);
            contentW = Math.min(ATLAS_W - contentX, tw + gutter * 2);
            contentH = Math.min(ATLAS_H - contentY, th + gutter * 2);
        } finally {
            g.dispose();
        }

        NativeImage pixels = new NativeImage(ATLAS_W, ATLAS_H, true);
        for (int py = 0; py < ATLAS_H; py++) {
            for (int px = 0; px < ATLAS_W; px++) {
                pixels.setPixelABGR(px, py, toABGR(image.getRGB(px, py)));
            }
        }
        texture.setPixels(pixels);
        texture.upload();

        cachedText = text;
        cachedAlpha = alpha;
        cachedScale = guiScale;
    }

    private static int toABGR(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static void ensureFont() {
        if (fontTried) {
            return;
        }
        fontTried = true;
        Path path = fontPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            font = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            font = null;
            WorldLoadingClient.LOGGER.warn("worldloading: failed to load LXGW WenKai from {}", path, e);
        }
    }

    private static Path fontPath() {
        Path p = Path.of(WLConfig.fontPath);
        if (p.isAbsolute()) {
            return p;
        }
        return FabricLoader.getInstance().getGameDir().resolve(p);
    }
}
