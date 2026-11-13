package com.example.videoloading.client;

import com.example.videoloading.client.StaticBackground;
import com.example.videoloading.early.LoadingLog;
import com.example.videoloading.early.VLConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Nothing to set up for rendering — the early window already owns the GL objects and
 * they survive handoff. This only decides who owns the resource-loading (B段) screen.
 */
public class VideoLoadingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VLConfig.load();
        LoadingLog.install();
        StaticBackground.init();

        // Drippy Loading Screen also takes over SplashOverlay. Both of us doing it at
        // once means double drawing or a crash, so step aside and let Drippy own B段.
        // Expose your progress to Drippy instead via a FancyMenu placeholder.
        boolean drippy = FabricLoader.getInstance().isModLoaded("drippyloadingscreen");
        if (drippy) {
            VLConfig.takeOverSplash = false;
        }
    }
}
