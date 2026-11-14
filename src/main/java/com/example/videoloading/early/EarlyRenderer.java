package com.example.videoloading.early;

import org.lwjgl.system.MemoryStack;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Minimal GL 3.2 core renderer: one program, one quad, two textures.
 *
 * Because the early window is *handed off* (same GLFW handle, same GL context),
 * every object created here survives into the resource-loading phase — the B段 mixin
 * just calls draw() again on the main thread. No texture rebuild, no video restart.
 *
 * Everything is drawn in NDC with a uRect uniform, and the viewport is read from GL
 * instead of being passed in, so the same call works before and after handoff.
 */
public final class EarlyRenderer {
    public static final int TEX_FRAME = 0;
    public static final int TEX_BG    = 1;
    public static final int TEX_LOG   = 2;

    private static final String VS =
            "#version 150 core\n" +
            "in vec2 aPos;\n" +
            "out vec2 vUV;\n" +
            "uniform vec4 uRect;\n" +
            "void main(){\n" +
            "  vUV = aPos;\n" +
            "  vec2 p = mix(uRect.xy, uRect.zw, aPos);\n" +
            "  gl_Position = vec4(p, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "#version 150 core\n" +
            "in vec2 vUV;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform int uUseTex;\n" +
            "uniform vec4 uColor;\n" +
            "uniform float uAlpha;\n" +
            "void main(){\n" +
            "  vec4 c = (uUseTex == 1) ? texture(uTex, vUV) : uColor;\n" +
            "  fragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    private static boolean inited = false;
    private static int program, vao, vbo;
    private static int uTex, uUseTex, uColor, uRect, uAlpha;
    private static final int[] tex = new int[3];
    private static final boolean[] texReady = new boolean[3];
    private static Font logFont;
    private static boolean logFontTried;
    private static String cachedLog;
    private static int cachedLogMax;
    private static int logW, logH;

    /** Tiny 5x7 digits + percent, so A段 has the same left-aligned label as B段. */
    private static final String[][] GLYPHS = {
            {"01110", "10001", "10011", "10101", "11001", "10001", "01110"}, // 0
            {"00100", "01100", "00100", "00100", "00100", "00100", "01110"}, // 1
            {"01110", "10001", "00001", "00010", "00100", "01000", "11111"}, // 2
            {"11110", "00001", "00001", "01110", "00001", "00001", "11110"}, // 3
            {"00010", "00110", "01010", "10010", "11111", "00010", "00010"}, // 4
            {"11111", "10000", "10000", "11110", "00001", "00001", "11110"}, // 5
            {"01110", "10000", "10000", "11110", "10001", "10001", "01110"}, // 6
            {"11111", "00001", "00010", "00100", "01000", "01000", "01000"}, // 7
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"}, // 8
            {"01110", "10001", "10001", "01111", "00001", "00001", "01110"}, // 9
            {"11001", "11011", "00110", "01100", "11000", "10011", "00011"}, // %
    };

    public static boolean isInited() { return inited; }

    /** Must run on the thread that owns the context (main thread, at bootstrap). */
    public static void init() {
        if (inited) return;
        int vs = compile(GL_VERTEX_SHADER, VS);
        int fs = compile(GL_FRAGMENT_SHADER, FS);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glBindAttribLocation(program, 0, "aPos");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException(glGetProgramInfoLog(program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);

        uTex    = glGetUniformLocation(program, "uTex");
        uUseTex = glGetUniformLocation(program, "uUseTex");
        uColor  = glGetUniformLocation(program, "uColor");
        uRect   = glGetUniformLocation(program, "uRect");
        uAlpha  = glGetUniformLocation(program, "uAlpha");
        System.out.println("[videoloading] shader uniforms tex=" + uTex + " useTex=" + uUseTex
                + " color=" + uColor + " rect=" + uRect + " alpha=" + uAlpha);

        // (x, y) triangle strip, y = 0 is the TOP of the rect
        float[] verts = { 0,0,  1,0,  0,1,  1,1 };
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(verts.length);
            fb.put(verts).flip();
            glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        }
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0);
        glBindVertexArray(0);

        for (int i = 0; i < tex.length; i++) {
            tex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex[i]);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        inited = true;
    }

    /** RGBA8 pixel upload. Caller owns `data` and frees it afterwards. */
    public static void upload(int slot, ByteBuffer data, int w, int h) {
        if (!inited || data == null) return;
        glBindTexture(GL_TEXTURE_2D, tex[slot]);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        glBindTexture(GL_TEXTURE_2D, 0);
        texReady[slot] = true;
    }

    public static boolean isReady(int slot) { return texReady[slot]; }

    public static void useFallbackBackground() { texReady[TEX_FRAME] = false; }

    /**
     * Draws background + progress bar. Saves and restores every piece of GL state it
     * touches, so it is safe to call from inside Minecraft's own overlay render.
     */
    public static void draw(float progress, float alpha) {
        draw(progress, alpha, -1, -1, null);
    }

    /**
     * @param vw explicit drawable width; use -1 to read GL_VIEWPORT
     * @param vh explicit drawable height; use -1 to read GL_VIEWPORT
     */
    public static void draw(float progress, float alpha, int vw, int vh) {
        draw(progress, alpha, vw, vh, null);
    }

    public static void draw(float progress, float alpha, int vw, int vh, String label) {
        if (!inited) return;

        IntBuffer vpBuf = null;
        int[] vp = new int[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vpBuf = stack.mallocInt(4);
            glGetIntegerv(GL_VIEWPORT, vpBuf);
            vp[0] = vpBuf.get(0); vp[1] = vpBuf.get(1); vp[2] = vpBuf.get(2); vp[3] = vpBuf.get(3);
        }
        if (vw <= 0) vw = Math.max(1, vp[2]);
        if (vh <= 0) vh = Math.max(1, vp[3]);

        boolean blendWas = glIsEnabled(GL_BLEND);
        boolean depthWas = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWas = glIsEnabled(GL_CULL_FACE);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);
        glBindVertexArray(vao);
        glUniform1i(uTex, 0);

        // ---- background: current video frame, else the still image, else black ----
        int slot = texReady[TEX_FRAME] ? TEX_FRAME : (texReady[TEX_BG] ? TEX_BG : -1);
        if (slot >= 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, tex[slot]);
            glUniform1i(uUseTex, 1);
            glUniform4f(uColor, 1f, 1f, 1f, 1f);
        } else {
            glUniform1i(uUseTex, 0);
            glUniform4f(uColor, 0f, 0f, 0f, 1f);
        }
        glUniform1f(uAlpha, alpha);
        glUniform4f(uRect, -1f, 1f, 1f, -1f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // ---- progress bar ----
        float px0 = (1f - VLConfig.barWidth) * 0.5f * vw;
        float px1 = px0 + VLConfig.barWidth * vw;
        float py0 = VLConfig.barY * vh;
        float py1 = py0 + VLConfig.barHeight;
        float x0 = px0 / vw * 2f - 1f;
        float x1 = px1 / vw * 2f - 1f;
        float y0 = 1f - py0 / vh * 2f;
        float y1 = 1f - py1 / vh * 2f;

        glUniform1i(uUseTex, 0);
        glUniform1f(uAlpha, alpha);
        glUniform4f(uRect, x0, y0, x1, y1);
        rgba(VLConfig.barBgColor, 1f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glUniform4f(uRect, x0, y0, x0 + (x1 - x0) * clamp01(progress), y1);
        rgba(VLConfig.barColor, 1f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        if (label != null && !label.isEmpty()) {
            drawBitmapText(label, vw, vh, alpha);
        }
        drawLogQuad(vw, vh, alpha);

        // ---- restore ----
        glUseProgram(prevProgram);
        glBindVertexArray(prevVao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, prevTex);
        if (!blendWas) glDisable(GL_BLEND);
        if (depthWas) glEnable(GL_DEPTH_TEST);
        if (cullWas) glEnable(GL_CULL_FACE);
        glViewport(vp[0], vp[1], vp[2], vp[3]);
    }

    /** Rasterise one raw log line with LXGW WenKai and upload it as TEX_LOG. */
    public static void uploadLog(String text, int maxWidth) {
        if (text == null || text.isBlank()) return;
        Font f = logFont();
        if (f == null) return;
        if (text.equals(cachedLog) && maxWidth == cachedLogMax) return;

        Font awt = f.deriveFont(14f);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = measure.createGraphics();
        mg.setFont(awt);
        FontMetrics fm = mg.getFontMetrics();
        String shown = text;
        int tw = fm.stringWidth(shown);
        if (tw > maxWidth) {
            while (shown.length() > 1 && fm.stringWidth(shown + "...") > maxWidth) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "...";
            tw = fm.stringWidth(shown);
        }
        int th = fm.getAscent() + fm.getDescent();
        mg.dispose();
        int iw = Math.max(1, tw + 4);
        int ih = Math.max(1, th + 4);

        BufferedImage image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(awt);
            int baseline = 2 + fm.getAscent();
            g.setColor(new Color(0, 0, 0, 210));
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox != 0 || oy != 0) g.drawString(shown, 2 + ox, baseline + oy);
                }
            }
            g.setColor(Color.WHITE);
            g.drawString(shown, 2, baseline);
        } finally {
            g.dispose();
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(iw * ih * 4);
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                int argb = image.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >>> 24) & 0xFF));
            }
        }
        buf.flip();
        upload(TEX_LOG, buf, iw, ih);
        cachedLog = text;
        cachedLogMax = maxWidth;
        logW = iw;
        logH = ih;
    }

    private static void drawLogQuad(int vw, int vh, float alpha) {
        if (!texReady[TEX_LOG] || logW <= 0 || logH <= 0) return;
        int drawH = 14;
        int drawW = Math.max(1, Math.round(logW / (float) logH * drawH));
        int x = Math.round(vw * 0.03f);
        int y = Math.round(vh * 0.72f) - drawH / 2;
        float x0 = x / (float) vw * 2f - 1f;
        float x1 = (x + drawW) / (float) vw * 2f - 1f;
        float y0 = 1f - y / (float) vh * 2f;
        float y1 = 1f - (y + drawH) / (float) vh * 2f;
        glUniform1i(uUseTex, 1);
        glUniform1f(uAlpha, alpha);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex[TEX_LOG]);
        glUniform4f(uRect, x0, y0, x1, y1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static Font logFont() {
        if (logFontTried) return logFont;
        logFontTried = true;
        try (InputStream in = Files.newInputStream(VLConfig.resolve(VLConfig.fontPath))) {
            logFont = Font.createFont(Font.TRUETYPE_FONT, in);
            System.out.println("[videoloading] log font loaded: " + VLConfig.fontPath);
        } catch (Throwable t) {
            System.out.println("[videoloading] log font unavailable: " + t);
        }
        return logFont;
    }

    private static void drawBitmapText(String text, int vw, int vh, float alpha) {
        int scale = 2;
        int glyphW = 5 * scale;
        int gap = scale;
        int glyphH = 7 * scale;
        int tx = Math.round(vw * 0.03f);
        int top = Math.round(vh * 0.78f) - glyphH / 2;
        int totalW = text.length() * glyphW + Math.max(0, text.length() - 1) * gap;

        // Left aligned; if it would run into the right edge, shrink (same intent as B).
        int maxW = Math.max(1, vw - tx * 2);
        if (totalW > maxW && totalW > 0) {
            float fit = maxW / (float) totalW;
            scale = Math.max(1, Math.round(scale * fit));
            glyphW = 5 * scale;
            gap = scale;
            glyphH = 7 * scale;
            top = Math.round(vh * 0.78f) - glyphH / 2;
        }

        glUniform1i(uUseTex, 0);
        glUniform1f(uAlpha, alpha);

        // Four dark offsets then the white glyph, same readability trick as B段.
        int[] ox = {-scale, scale, 0, 0, 0};
        int[] oy = {0, 0, -scale, scale, 0};
        for (int pass = 0; pass < 5; pass++) {
            rgba(pass == 4 ? 0xFFFFFFFF : 0xCC000000, 1f);
            int cx = tx + ox[pass];
            int cy = top + oy[pass];
            for (int i = 0; i < text.length(); i++) {
                String[] glyph = glyph(text.charAt(i));
                if (glyph == null) continue;
                int gx = cx + i * (glyphW + gap);
                for (int row = 0; row < 7; row++) {
                    String line = glyph[row];
                    for (int col = 0; col < 5; col++) {
                        if (line.charAt(col) != '1') continue;
                        int px = gx + col * scale;
                        int py = cy + row * scale;
                        float nx0 = px / (float) vw * 2f - 1f;
                        float nx1 = (px + scale) / (float) vw * 2f - 1f;
                        float ny0 = 1f - py / (float) vh * 2f;
                        float ny1 = 1f - (py + scale) / (float) vh * 2f;
                        glUniform4f(uRect, nx0, ny0, nx1, ny1);
                        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    }
                }
            }
        }
    }

    private static String[] glyph(char c) {
        if (c >= '0' && c <= '9') return GLYPHS[c - '0'];
        if (c == '%') return GLYPHS[10];
        return null;
    }

    private static void rgba(int argb, float mul) {
        float a = ((argb >> 24) & 0xFF) / 255f * mul;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        glUniform4f(uColor, r, g, b, a);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException(glGetShaderInfoLog(s));
        }
        return s;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private EarlyRenderer() {}
}
