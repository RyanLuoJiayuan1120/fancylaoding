package com.example.videoloading.early;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Wraps KnotClassDelegate.mixinTransformer with a dynamic proxy. Two jobs per call:
 *   1. count transforms -> the only usable signal for "mixin phase progress"
 *   2. pump GLFW events  -> this runs on the MAIN thread, which is the only place
 *                           glfwPollEvents is legal (macOS: must be thread 0)
 *
 * Nothing here touches a Minecraft class. Everything is wrapped in try/catch:
 * if Fabric internals move, we degrade to "no progress" instead of breaking startup.
 */
public final class MixinProbe {
    private static volatile boolean installed = false;
    private static volatile int count = 0;
    private static volatile int expected = 0;

    public static int count() { return count; }
    public static boolean isInstalled() { return installed; }

    public static void install() {
        if (installed) return;
        try {
            ClassLoader cl = MixinProbe.class.getClassLoader();          // KnotClassLoader
            Field delegateField = cl.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(cl);                     // KnotClassDelegate

            Field transformerField = delegate.getClass().getDeclaredField("mixinTransformer");
            transformerField.setAccessible(true);
            Object original = transformerField.get(delegate);
            if (original == null) {
                System.out.println("[videoloading] MixinProbe: transformer not ready, will retry");
                return;                                                  // not initialised yet -> retry later
            }

            Object proxy = Proxy.newProxyInstance(
                    original.getClass().getClassLoader(),
                    original.getClass().getInterfaces(),
                    (p, method, args) -> {
                        if ("transform".equals(method.getName())) {
                            count++;
                            EarlyWindow.pollOnMainThread();
                        }
                        return method.invoke(original, args);
                    });

            transformerField.set(delegate, proxy);
            installed = true;
            System.out.println("[videoloading] MixinProbe installed (transformer=" + original.getClass().getName() + ")");
        } catch (Throwable t) {
            // degraded: no transform counting, no event pumping from here
            System.out.println("[videoloading] MixinProbe unavailable: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Transform counts arrive in bursts and the total is unknown up front, so map
     * them through a saturating curve: monotonic, never exceeds 1, looks better than
     * a linear estimate against a guessed denominator.
     */
    public static float progress() {
        float k = expected > 0 ? expected : 1200f;
        return (float) (1.0 - Math.exp(-count / k));
    }

    public static void setExpected(int n) { expected = n; }

    private MixinProbe() {}
}
