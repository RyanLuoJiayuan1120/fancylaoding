package com.example.videoloading.early;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Earliest hook available on Fabric. Runs on the MAIN thread while Mixin boots,
 * which is exactly what we need: GLFW window creation must happen on the main thread
 * (macOS additionally requires that thread to be thread 0, i.e. -XstartOnFirstThread).
 *
 * HARD RULE: do not reference, import or statically touch any Minecraft class here.
 * Mixin throws MixinTargetAlreadyLoadedException for any class loaded this early, and
 * Fabric only considers `preLaunch` a safe point for class loading.
 */
public class EarlyBootstrap implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        try {
            LoadingLog.install();
            LoadingLog.push("Starting Minecraft...");
            VLConfig.load();
            System.out.println("[videoloading] onLoad enabled=" + VLConfig.enabled
                    + " adopt=" + VLConfig.adoptWindow + " size=" + VLConfig.windowWidth + "x" + VLConfig.windowHeight
                    + " fps=" + VLConfig.fps + " frames=" + VLConfig.framesDir);
            if (!VLConfig.enabled) return;
            EarlyWindow.create();      // creates window + GL objects on the main thread
            MixinProbe.install();      // may fail here (transformer not ready) -> retried at preLaunch
            System.out.println("[videoloading] after create: handle=" + (EarlyWindow.hasHandle() ? "yes" : "no")
                    + " probe=" + MixinProbe.isInstalled());
        } catch (Throwable t) {
            VLConfig.enabled = false;  // never let this break startup
            System.out.println("[videoloading] onLoad failed: " + t);
            t.printStackTrace(System.out);
        }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
