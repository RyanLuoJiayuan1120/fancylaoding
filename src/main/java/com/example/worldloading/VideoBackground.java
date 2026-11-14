package com.example.worldloading;

import com.example.fancyloading.video.FfmpegProvisioner;
import com.example.fancyloading.video.FfmpegVideoSource;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loading background. Prefers an MP4 decoded by the provisioned FFmpeg; falls
 * back to the bundled 75-frame PNG sequence when no video is configured.
 *
 * <p>COVER geometry is recomputed on every render call. Never cache the
 * drawable size: after the A -> B hand-off it can revert to the old value.</p>
 */
public final class VideoBackground {

    private static final Identifier[] FRAMES = buildFrameIds();
    private static final Identifier VIDEO_ID =
            Identifier.fromNamespaceAndPath("worldloading", "loading/video_frame");

    private static long startNanos;
    private static volatile FfmpegVideoSource videoSource;
    private static volatile boolean videoStarted;
    private static DynamicTexture videoTexture;
    private static NativeImage videoImage;

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

    /** Start the optional MP4 decoder. Safe to call from client init. */
    public static void startVideoAsync() {
        if (videoStarted) return;
        videoStarted = true;
        Path video = resolve(WLConfig.videoPath);
        if (!Files.isRegularFile(video)) {
            WorldLoadingClient.LOGGER.info("worldloading: no MP4 at {}, using bundled frames", video);
            return;
        }
        WorldLoadingClient.LOGGER.info("worldloading: MP4 configured at {}", video);
        FfmpegProvisioner.ensureAsync(FabricLoader.getInstance().getGameDir(), msg ->
                WorldLoadingClient.LOGGER.info("worldloading: {}", msg)
        ).thenAccept(ffmpeg -> {
            try {
                videoSource = FfmpegVideoSource.start(
                        ffmpeg, video, WLConfig.videoWidth, WLConfig.videoHeight, WLConfig.videoFps);
                WorldLoadingClient.LOGGER.info("worldloading: FFmpeg video started {}x{}@{}",
                        WLConfig.videoWidth, WLConfig.videoHeight, WLConfig.videoFps);
            } catch (Throwable t) {
                WorldLoadingClient.LOGGER.warn("worldloading: FFmpeg video failed, using frames", t);
            }
        }).exceptionally(t -> {
            WorldLoadingClient.LOGGER.warn("worldloading: FFmpeg unavailable, using frames: {}", t.toString());
            return null;
        });
    }

    public static void begin() {
        startNanos = System.nanoTime();
        if (!videoStarted) startVideoAsync();
    }

    public static void stop() {
        startNanos = 0L;
        FfmpegVideoSource source = videoSource;
        videoSource = null;
        if (source != null) source.close();
        videoStarted = false;
    }

    public static void render(GuiGraphics ctx) {
        if (!WLConfig.videoEnabled) {
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

        if (renderVideo(ctx, w, h)) {
            dim(ctx, w, h);
            return;
        }
        renderFrames(ctx, w, h);
        dim(ctx, w, h);
    }

    private static boolean renderVideo(GuiGraphics ctx, int w, int h) {
        FfmpegVideoSource source = videoSource;
        if (source == null || !source.hasFirstFrame()) {
            return false;
        }
        ByteBuffer frame = source.takeFrame();
        if (frame != null) {
            ensureVideoTexture(source.width(), source.height());
            if (videoImage != null && videoTexture != null) {
                ByteBuffer dst = MemoryUtil.memByteBuffer(
                        videoImage.getPointer(), source.width() * source.height() * 4);
                dst.put(frame.duplicate());
                videoTexture.upload();
            }
        }
        if (videoTexture == null) return false;
        drawCover(ctx, VIDEO_ID, source.width(), source.height(), w, h);
        return true;
    }

    private static void ensureVideoTexture(int width, int height) {
        if (videoTexture != null) return;
        videoImage = new NativeImage(width, height, true);
        videoTexture = new DynamicTexture("worldloading-video", width, height, true);
        videoTexture.setPixels(videoImage);
        videoTexture.upload();
        Minecraft.getInstance().getTextureManager().register(VIDEO_ID, videoTexture);
        WorldLoadingClient.LOGGER.info("worldloading: video texture {}x{} created", width, height);
    }

    private static void renderFrames(GuiGraphics ctx, int w, int h) {
        if (FRAMES.length == 0) return;
        long elapsed = System.nanoTime() - startNanos;
        int frame = (int) ((elapsed / 1_000_000_000L * WLConfig.videoFps) % FRAMES.length);
        drawCover(ctx, FRAMES[frame], WLConfig.videoWidth, WLConfig.videoHeight, w, h);
    }

    private static void drawCover(GuiGraphics ctx, Identifier id, int srcW, int srcH, int w, int h) {
        float scale = Math.max(w / (float) srcW, h / (float) srcH);
        int dw = Math.max(1, Math.round(srcW * scale));
        int dh = Math.max(1, Math.round(srcH * scale));
        int x = (w - dw) / 2;
        int y = (h - dh) / 2;
        // 1.21.11: blit(id, x0, y0, x1, y1, u0, u1, v0, v1).
        ctx.blit(id, x, y, x + dw, y + dh, 0f, 1f, 0f, 1f);
    }

    private static void dim(GuiGraphics ctx, int w, int h) {
        if (WLConfig.videoDim > 0f) {
            int alpha = Math.round(255f * WLConfig.clamp(WLConfig.videoDim, 0f, 0.9f));
            ctx.fill(0, 0, w, h, alpha << 24);
        }
    }

    private static Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
    }
}
