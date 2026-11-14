package com.example.worldloading;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entry point: config only, no screen state is held here. */
public final class WorldLoadingClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("worldloading");

    @Override
    public void onInitializeClient() {
        WLConfig.load();
        XingkaiFont.init();
        VideoBackground.startVideoAsync();
        LOGGER.info("worldloading v2 ready: enabled={}, barWidth={}, barY={}, textY={}, video={}",
                WLConfig.enabled, WLConfig.barWidth, WLConfig.barY, WLConfig.textY,
                WLConfig.videoEnabled);
    }
}
