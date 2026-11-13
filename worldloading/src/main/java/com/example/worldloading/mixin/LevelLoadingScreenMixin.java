package com.example.worldloading.mixin;

import com.example.worldloading.BarRenderer;
import com.example.worldloading.VideoBackground;
import com.example.worldloading.WLConfig;
import com.example.worldloading.WorldProgress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11 target. The old docs' ReceivingLevelScreen / GenericDirtMessageScreen
 * do not exist in this version; LevelLoadingScreen covers world generation and
 * client level receive. Only DrawContext fallback is used here.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    @Shadow
    private LevelLoadTracker loadTracker;

    @Inject(method = "renderChunks", at = @At("HEAD"), cancellable = true, require = 0)
    private static void wl$replaceChunkGrid(GuiGraphics g, int xCenter, int yCenter,
                                            int size, int margin, ChunkLoadStatusView view,
                                            CallbackInfo ci) {
        if (WLConfig.enabled) {
            ci.cancel();
        }
    }

    @Inject(method = "drawProgressBar", at = @At("HEAD"), cancellable = true, require = 0)
    private void wl$hideVanillaBar(GuiGraphics g, int x, int y, int width, int height,
                                   float progress, CallbackInfo ci) {
        if (WLConfig.enabled) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void wl$drawV2(GuiGraphics g, int mouseX, int mouseY, float partialTick,
                           CallbackInfo ci) {
        if (!WLConfig.enabled) {
            return;
        }
        WorldProgress.begin(WorldProgress.Kind.GENERATING);

        float p;
        if (loadTracker != null && loadTracker.hasProgress()) {
            p = WLConfig.clamp(loadTracker.serverProgress(), 0f, 1f);
            WorldProgress.set(p);
            if (p >= 0.999f) {
                WorldProgress.complete();
            }
        } else {
            p = WorldProgress.estimate(WLConfig.estimateK, WLConfig.estimateCap);
            WorldProgress.set(p);
        }

        // Read guiWidth/guiHeight every frame: after an A->B window transition
        // the drawable size can revert to the original value. Never cache it.
        VideoBackground.render(g);
        BarRenderer.drawBar(g, WorldProgress.get(), 1f);
        BarRenderer.drawText(g, Math.round(WorldProgress.get() * 100f) + "%", 1f);
    }

    @Inject(method = "onClose", at = @At("TAIL"), require = 0)
    private void wl$onClose(CallbackInfo ci) {
        WorldProgress.end();
        VideoBackground.stop();
    }
}
