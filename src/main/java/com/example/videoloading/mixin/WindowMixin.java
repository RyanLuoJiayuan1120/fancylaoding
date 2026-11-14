package com.example.videoloading.mixin;

import com.example.videoloading.early.EarlyWindow;
import com.example.videoloading.early.VLConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.mojang.blaze3d.platform.Window;

/**
 * Hand the early window over to Minecraft.
 *
 * Do NOT cancel Window.<init>: it also computes scaledWidth/Height, installs the six
 * GLFW callbacks, sets the error callback, vsync and the icon, and none of that would
 * happen. Wrapping just glfwCreateWindow lets all of it run against our handle.
 *
 * @WrapOperation instead of @Redirect: MixinExtras 0.5.0 on this dev runtime crashes its
 * RedirectFactory wrapper with "ArrayList cannot be cast to AnnotationNode", while
 * WrapOperation takes a different code path and works.
 *
 * If anything is off (adopt disabled, no early window) we simply call the real
 * glfwCreateWindow, so the mod degrades to vanilla behaviour instead of a null window.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"),
            require = 0
    )
    private static long videoloading$adoptEarlyWindow(int width, int height, CharSequence title,
                                                      long monitor, long share,
                                                      Operation<Long> original) {
        if (VLConfig.enabled && VLConfig.adoptWindow && EarlyWindow.hasHandle()) {
            long h = EarlyWindow.handoff(VLConfig.maxHoldMs);
            if (h != 0L) return h;
        }
        return original.call(width, height, title, monitor, share);
    }
}
