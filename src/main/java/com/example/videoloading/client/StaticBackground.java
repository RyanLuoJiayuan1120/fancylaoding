package com.example.videoloading.client;

import com.example.videoloading.early.VLConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B 段静态背景：直接取 config/videoloading/background.png（视频任意一帧）。
 * 文件在 client init 读入内存；GPU 纹理延迟到第一次 B 段 render 才创建，
 * 因为 client init 阶段 RenderSystem.getDevice() 还没初始化。
 */
public final class StaticBackground {

    private static final Identifier ID =
            Identifier.fromNamespaceAndPath("videoloading", "loading/background");

    private static boolean fileTried;
    private static int[] argbPixels;
    private static int imageW;
    private static int imageH;
    private static DynamicTexture texture;

    private StaticBackground() {
    }

    /** Safe during client init: file IO only, no GPU calls. */
    public static void init() {
        if (fileTried) return;
        fileTried = true;
        try {
            Path path = VLConfig.resolve(VLConfig.background);
            if (!Files.isRegularFile(path)) {
                System.out.println("[videoloading] B background missing: " + path);
                return;
            }
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                System.out.println("[videoloading] B background unreadable: " + path);
                return;
            }
            imageW = image.getWidth();
            imageH = image.getHeight();
            argbPixels = image.getRGB(0, 0, imageW, imageH, null, 0, imageW);
            System.out.println("[videoloading] B background file loaded: " + path
                    + " (" + imageW + "x" + imageH + ")");
        } catch (Throwable t) {
            argbPixels = null;
            System.out.println("[videoloading] B background file failed: " + t);
        }
    }

    /** COVER the current GUI area; returns false if no image is available. */
    public static boolean draw(GuiGraphics g, int w, int h) {
        init();
        if (texture == null) {
            createTexture();
        }
        if (texture == null || imageW <= 0 || imageH <= 0) return false;
        float scale = Math.max(w / (float) imageW, h / (float) imageH);
        int dw = Math.max(1, Math.round(imageW * scale));
        int dh = Math.max(1, Math.round(imageH * scale));
        int x = (w - dw) / 2;
        int y = (h - dh) / 2;
        g.enableScissor(0, 0, w, h);
        g.blit(ID, x, y, x + dw, y + dh, 0f, 1f, 0f, 1f);
        g.disableScissor();
        return true;
    }

    /** Must run on the render thread, after the GPU device exists. */
    private static void createTexture() {
        if (argbPixels == null || imageW <= 0 || imageH <= 0) return;
        try {
            NativeImage pixels = new NativeImage(imageW, imageH, true);
            for (int y = 0; y < imageH; y++) {
                int row = y * imageW;
                for (int x = 0; x < imageW; x++) {
                    pixels.setPixelABGR(x, y, toABGR(argbPixels[row + x]));
                }
            }
            texture = new DynamicTexture("videoloading-background", imageW, imageH, true);
            texture.setPixels(pixels);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(ID, texture);
            System.out.println("[videoloading] B background texture created");
        } catch (Throwable t) {
            texture = null;
            System.out.println("[videoloading] B background texture failed: " + t);
        }
    }

    private static int toABGR(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
