package com.example.videoloading.early;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * By preLaunch the mixin transformer is guaranteed to exist, so this is the reliable
 * place to install the probe. MC class loading (which is when transforms actually
 * happen) starts after this point, so we still capture the bulk of the mixin phase.
 */
public class PreLaunchHook implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            LoadingLog.install();
            System.out.println("[videoloading] preLaunch: installing probe");
            MixinProbe.install();
            System.out.println("[videoloading] preLaunch: probe=" + MixinProbe.isInstalled());
        } catch (Throwable t) {
            System.out.println("[videoloading] preLaunch: probe failed: " + t);
        }
    }
}
