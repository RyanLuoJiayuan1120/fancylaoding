package com.example.worldloading;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * Optional video background, rendered from a PNG frame sequence.
 *
 * <p>No H.264 decoder is embedded: the uploaded test clip is converted to
 * 480x270 PNG frames at 15 fps. Each frame is scaled to COVER the current
 * drawable size, recalculated on every render call. That is deliberate: after
 * the A -> B window/GL hand-off the drawable size can revert to the original
 * value, so a cached width/height (or a cached quad) would snap back to the
 * old size.</p>
 */
public final class VideoBackground {

    private static final Identifier[] FRAMES = buildFrameIds();

    private static long startNanos;

    private VideoBackground() {
    }

    private static Identifier[] buildFrameIds() {
        Identifier[] ids = new Identifier[WLConfig.videoFrames];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = Identifier.fromNamespaceAndPath("worldloading",
                    "textures/loading/frame_" + String.format("%03d", i) + ".png");
        }
        return ids;
    }

    public static void begin() {
        startNanos = System.nanoTime();
    }

    public static void stop() {
        startNanos = 0L;
    }

    public static void render(GuiGraphics ctx) {
        if (!WLConfig.videoEnabled || FRAMES.length == 0) {
            return;
        }
        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (startNanos == 0L) {
            begin();
        }

        long elapsed = System.nanoTime() - startNanos;
        int frame = (int) ((elapsed / 1_000_000_000L * WLConfig.videoFps) % FRAMES.length);

        // COVER: fill the current drawable area, crop overflow symmetrically.
        float scale = Math.max(w / (float) WLConfig.videoWidth,
                h / (float) WLConfig.videoHeight);
        int dw = Math.max(1, Math.round(WLConfig.videoWidth * scale));
        int dh = Math.max(1, Math.round(WLConfig.videoHeight * scale));
        int x = (w - dw) / 2;
        int y = (h - dh) / 2;

        // 1.21.11: blit(id, x0, y0, x1, y1, u0, u1, v0, v1).
        // Verified against GuiGraphics.innerBlit -> BlitRenderState: the ints are
        // absolute corners (x0,y0,x1,y1) and the floats are UV ranges (u0,u1,v0,v1).
        // The old (x, y, dw, dh, 0, 0, 1, 1) sampled the single bottom-left texel,
        // hence the solid-colour background.
        ctx.blit(FRAMES[frame], x, y, x + dw, y + dh, 0f, 1f, 0f, 1f);

        if (WLConfig.videoDim > 0f) {
            int alpha = Math.round(255f * WLConfig.clamp(WLConfig.videoDim, 0f, 0.9f));
            ctx.fill(0, 0, w, h, alpha << 24);
        }
    }
}
