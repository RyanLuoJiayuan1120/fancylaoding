package com.example.worldloading;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** v2 DrawContext fallback: full-width bar at 0.84h, text optional. */
public final class BarRenderer {

    private BarRenderer() {
    }

    public static void drawBar(GuiGraphics ctx, float progress, float alpha) {
        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int bw = Math.max(1, Math.round(w * WLConfig.barWidth));
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        // barHeight is defined in physical pixels (A-segment reference); GuiGraphics
        // works in scaled GUI units, so divide by the GUI scale.
        int bh = Math.max(1, Math.round(WLConfig.barHeight / (float) guiScale));
        int x = (w - bw) / 2;
        int y = Math.min(h - bh, Math.round(h * WLConfig.barY));

        ctx.fill(x, y, x + bw, y + bh, withAlpha(WLConfig.barBgColor, alpha));
        int filled = Math.round(bw * WLConfig.clamp(progress, 0f, 1f));
        if (filled > 0) {
            ctx.fill(x, y, x + filled, y + bh, withAlpha(WLConfig.barColor, alpha));
        }
    }

    /**
     * Font is optional; layout stays identical whether or not the TTF is present.
     */
    public static void drawText(GuiGraphics ctx, String text, float alpha) {
        if (!WLConfig.showText || !XingkaiFont.isReady()) {
            return;
        }
        XingkaiFont.draw(ctx, text, WLConfig.textY, WLConfig.textPadding,
                WLConfig.fontSize, alpha);
    }

    public static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * WLConfig.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
