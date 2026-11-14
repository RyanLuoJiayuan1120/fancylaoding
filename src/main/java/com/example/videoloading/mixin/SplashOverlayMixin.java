package com.example.videoloading.mixin;

import com.example.videoloading.client.LogTexture;
import com.example.videoloading.client.StaticBackground;
import com.example.videoloading.early.LoadingLog;
import com.example.videoloading.early.ProgressBus;
import com.example.videoloading.early.VLConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * B 段：原版 render 跑完后，用 GuiGraphics 再把背景/进度条盖上去。
 *
 * <p>TAIL instead of HEAD+cancel: vanilla's render owns the fade-out and the
 * onFinish callback that removes this overlay. Cancelling it left the game stuck
 * at 100% forever. Drawing after vanilla covers the Mojang logo while keeping the
 * lifecycle intact.</p>
 *
 * <p>Text is a tiny 5x7 bitmap drawn with fill(), not drawString: the text shader
 * is part of the resource pack and does not exist until this very reload finishes.
 * That also keeps A and B labels visually identical.</p>
 */
@Mixin(LoadingOverlay.class)
public class SplashOverlayMixin {

    @Shadow @Final private ReloadInstance reload;

    private static long videoloading$lastLogNanos = 0L;
    private static float videoloading$smooth = 0f;

    private static final String[][] GLYPHS = {
            {"01110", "10001", "10011", "10101", "11001", "10001", "01110"}, // 0
            {"00100", "01100", "00100", "00100", "00100", "00100", "01110"}, // 1
            {"01110", "10001", "00001", "00010", "00100", "01000", "11111"}, // 2
            {"11110", "00001", "00001", "01110", "00001", "00001", "11110"}, // 3
            {"00010", "00110", "01010", "10010", "11111", "00010", "00010"}, // 4
            {"11111", "10000", "10000", "11110", "00001", "00001", "11110"}, // 5
            {"01110", "10000", "10000", "11110", "10001", "10001", "01110"}, // 6
            {"11111", "00001", "00010", "00100", "01000", "01000", "01000"}, // 7
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"}, // 8
            {"01110", "10001", "10001", "01111", "00001", "00001", "01110"}, // 9
            {"11001", "11011", "00110", "01100", "11000", "10011", "00011"}, // %
    };

    // TAIL, not HEAD+cancel: vanilla's render owns the fade-out and the
    // onFinish callback that removes this overlay. Cancelling it left the game
    // permanently stuck at 100% on a black screen. Drawing after vanilla covers
    // the Mojang logo while leaving that lifecycle intact.
    @Inject(method = "render", at = @At("TAIL"))
    private void videoloading$render(GuiGraphics g, int mouseX, int mouseY, float deltaTicks,
                                     CallbackInfo ci) {
        if (!VLConfig.enabled || !VLConfig.takeOverSplash) {
            return;
        }

        ProgressBus.finishMixin();
        float raw = reload.getActualProgress();
        videoloading$smooth = videoloading$smooth * 0.95f + raw * 0.05f;
        if (raw >= 0.999f) {
            videoloading$smooth = 1f;
        }
        ProgressBus.setResource(videoloading$smooth);
        if (reload.isDone()) {
            ProgressBus.markStartupDone();
        }

        int w = g.guiWidth();
        int h = g.guiHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Opaque background. A texture would need the resource manager, which is
        // exactly what is still reloading during B; a gradient is always available
        // and, unlike the old raw-GL path, is actually visible.
        g.fillGradient(0, 0, w, h, 0xFF0B2F2A, 0xFF061411);
        StaticBackground.draw(g, w, h);

        // Same layout as the v2 world-loading bar: full width, 0.84h.
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int bh = Math.max(1, Math.round(VLConfig.barHeight / (float) guiScale));
        int by = Math.min(h - bh, Math.round(h * VLConfig.barY));
        g.fill(0, by, w, by + bh, 0xAA000000);
        int filled = Math.round(w * clamp01(ProgressBus.overall()));
        if (filled > 0) {
            g.fill(0, by, filled, by + bh, 0xFFFFFFFF);
        }

        String log = LoadingLog.latest();
        if (log == null || LoadingLog.isStale(800)) {
            log = "Loading resource packs...";
        }
        LogTexture.draw(g, log, w, h);

        String text = Math.round(clamp01(ProgressBus.overall()) * 100f) + "%";
        drawBitmapText(g, text, w, h);

        long now = System.nanoTime();
        if (now - videoloading$lastLogNanos > 1_000_000_000L) {
            videoloading$lastLogNanos = now;
            System.out.printf("[videoloading] B render raw=%.2f smooth=%.2f overall=%.2f size=%dx%d%n",
                    raw, videoloading$smooth, ProgressBus.overall(), w, h);
        }
    }

    private static void drawBitmapText(GuiGraphics g, String text, int w, int h) {
        int scale = Math.max(1, Math.round(h * 0.029f / 7f));
        int glyphW = 5 * scale;
        int gap = scale;
        int glyphH = 7 * scale;
        int tx = Math.round(w * 0.03f);
        int top = Math.round(h * 0.78f) - glyphH / 2;
        int totalW = text.length() * glyphW + Math.max(0, text.length() - 1) * gap;
        if (totalW > w - tx * 2 && totalW > 0) {
            float fit = (w - tx * 2) / (float) totalW;
            scale = Math.max(1, Math.round(scale * fit));
            glyphW = 5 * scale;
            gap = scale;
            glyphH = 7 * scale;
            top = Math.round(h * 0.78f) - glyphH / 2;
        }

        // Same 4-direction dark outline as the A segment, then white.
        int[] ox = {-scale, scale, 0, 0, 0};
        int[] oy = {0, 0, -scale, scale, 0};
        for (int pass = 0; pass < 5; pass++) {
            int color = pass == 4 ? 0xFFFFFFFF : 0xC0000000;
            int px = ox[pass];
            int py = oy[pass];
            for (int i = 0; i < text.length(); i++) {
                String[] glyph = glyph(text.charAt(i));
                if (glyph == null) continue;
                int gx = tx + i * (glyphW + gap) + px;
                for (int row = 0; row < 7; row++) {
                    String line = glyph[row];
                    for (int col = 0; col < 5; col++) {
                        if (line.charAt(col) == '1') {
                            int fx = gx + col * scale;
                            int fy = top + row * scale + py;
                            g.fill(fx, fy, fx + scale, fy + scale, color);
                        }
                    }
                }
            }
        }
    }

    private static String[] glyph(char c) {
        if (c >= '0' && c <= '9') return GLYPHS[c - '0'];
        if (c == '%') return GLYPHS[10];
        return null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
