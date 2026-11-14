package com.example.fancyloading.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Picks a decoder for the current platform.
 *
 * <p>Desktop: provisioned FFmpeg first (fast, handles 4K), JCodec as the
 * offline/install-failure fallback.</p>
 *
 * <p>Android/FCL: never try FFmpeg. Android 10+ denies execve() on
 * app-private files, so the download can only fail; go straight to the
 * pure-Java JCodec decoder.</p>
 */
public final class VideoSources {

    private VideoSources() {
    }

    /** Prefer {@code video-mobile.mp4} next to a 4K {@code video.mp4}. */
    public static Path mobileVariant(Path video, String quality) {
        Path parent = video.getParent();
        if (parent == null) return video;
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".mp4";
        if ("hd".equalsIgnoreCase(quality)) {
            Path hd = parent.resolve(stem + "-mobile-hd" + ext);
            if (Files.isRegularFile(hd)) return hd;
        }
        Path sd = parent.resolve(stem + "-mobile" + ext);
        return Files.isRegularFile(sd) ? sd : video;
    }

    private static int[] decoderSize(Path source, int width, int height) {
        String name = source.getFileName().toString();
        if (name.endsWith("-mobile-hd.mp4")) return new int[]{1920, 1080};
        if (name.endsWith("-mobile.mp4")) return new int[]{1280, 720};
        return new int[]{width, height};
    }

    /**
     * Starts decoding on a worker thread and hands the source to {@code sink}.
     * The sink may be called from any thread; keep the field volatile.
     */
    public static void startAsync(Path video, int width, int height, int fps,
                                  String quality, Path gameDir, Consumer<String> log,
                                  Consumer<VideoSource> sink) {
        Path sourceFile = mobileVariant(video, quality);
        if (FfmpegPaths.isAndroid()) {
            int[] size = decoderSize(sourceFile, width, height);
            log.accept("Android: using JCodec " + size[0] + "x" + size[1]
                    + ", source=" + sourceFile);
            sink.accept(JCodecVideoSource.start(sourceFile, size[0], size[1], fps, log));
            return;
        }

        FfmpegProvisioner.ensureAsync(gameDir, log).thenAccept(ffmpeg -> {
            try {
                sink.accept(FfmpegVideoSource.start(ffmpeg, video, width, height, fps));
                log.accept("FFmpeg video started: " + width + "x" + height + "@" + fps);
            } catch (java.io.IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).exceptionally(t -> {
            log.accept("FFmpeg unavailable, trying JCodec: " + t);
            try {
                int[] size = decoderSize(sourceFile, width, height);
                sink.accept(JCodecVideoSource.start(sourceFile, size[0], size[1], fps, log));
                log.accept("JCodec video started: " + size[0] + "x" + size[1] + "@" + fps
                        + " source=" + sourceFile);
            } catch (Throwable jt) {
                log.accept("JCodec video failed: " + jt);
            }
            return null;
        });
    }
}
