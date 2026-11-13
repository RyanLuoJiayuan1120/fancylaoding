package com.example.videoloading.client;

import com.example.videoloading.early.VLConfig;
import com.mojang.blaze3d.platform.NativeImage;
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

/**
 * B 段日志行：AWT 把一行原始日志画进固定纹理，再走 GuiGraphics.blit。
 * 不使用 Minecraft 的字体管线，因为资源重载期间 text shader 还不存在。
 */
public final class LogTexture {

    private static final int W = 1024;
    private static final int H = 40;
    private static final Identifier ID =
            Identifier.fromNamespaceAndPath("videoloading", "font/log_line");

    private static Font font;
    private static boolean fontTried;
    private static DynamicTexture texture;
    private static String cached;
    private static int contentX;
    private static int contentY;
    private static int contentW;
    private static int contentH;

    private LogTexture() {
    }

    public static void draw(GuiGraphics g, String text, int guiW, int guiH) {
        if (text == null || text.isBlank()) return;
        Font f = font();
        if (f == null) return;

        if (texture == null) {
            texture = new DynamicTexture("videoloading-log-line", W, H, true);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
        }
        if (!text.equals(cached)) {
            rebuild(f, text);
        }
        if (contentW <= 0 || contentH <= 0) return;

        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int drawH = Math.max(1, Math.round(14f / guiScale));
        int drawW = Math.max(1, Math.round(contentW / (float) contentH * drawH));
        int maxW = Math.max(1, guiW - Math.round(guiW * 0.06f));
        if (drawW > maxW) {
            float fit = maxW / (float) drawW;
            drawW = maxW;
            drawH = Math.max(1, Math.round(drawH * fit));
        }
        int x = Math.round(guiW * 0.03f);
        int y = Math.round(guiH * 0.72f) - drawH / 2;

        float u0 = contentX / (float) W;
        float u1 = (contentX + contentW) / (float) W;
        float v0 = contentY / (float) H;
        float v1 = (contentY + contentH) / (float) H;
        g.blit(ID, x, y, x + drawW, y + drawH, u0, u1, v0, v1);
    }

    private static void rebuild(Font f, String text) {
        Font awt = f.deriveFont(14f);
        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure = measureImage.createGraphics();
        measure.setFont(awt);
        FontMetrics fm = measure.getFontMetrics();
        String shown = text;
        int tw = fm.stringWidth(shown);
        int maxText = W - 8;
        if (tw > maxText) {
            while (shown.length() > 1 && fm.stringWidth(shown + "...") > maxText) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown += "...";
            tw = fm.stringWidth(shown);
        }
        int th = fm.getAscent() + fm.getDescent();
        int tx = 2;
        int ty = Math.max(0, (H - th) / 2);
        int baseline = ty + fm.getAscent();
        measure.dispose();

        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(awt);
            g.setColor(new Color(0, 0, 0, 210));
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox != 0 || oy != 0) g.drawString(shown, tx + ox, baseline + oy);
                }
            }
            g.setColor(Color.WHITE);
            g.drawString(shown, tx, baseline);
        } finally {
            g.dispose();
        }

        NativeImage pixels = new NativeImage(W, H, true);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                pixels.setPixelABGR(x, y, toABGR(image.getRGB(x, y)));
            }
        }
        texture.setPixels(pixels);
        texture.upload();

        contentX = 0;
        contentY = Math.max(0, ty - 1);
        contentW = Math.min(W, tw + 4);
        contentH = Math.min(H - contentY, th + 2);
        cached = text;
    }

    private static Font font() {
        if (fontTried) return font;
        fontTried = true;
        try (InputStream in = Files.newInputStream(VLConfig.resolve(VLConfig.fontPath))) {
            font = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Throwable t) {
            System.out.println("[videoloading] B log font unavailable: " + t);
        }
        return font;
    }

    private static int toABGR(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
