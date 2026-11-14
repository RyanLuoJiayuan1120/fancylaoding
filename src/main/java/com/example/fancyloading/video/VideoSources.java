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
    public static Path mobileVariant(Path video) {
        Path parent = video.getParent();
        if (parent == null) return video;
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String mobileName = (dot > 0 ? name.substring(0, dot) : name)
                + "-mobile" + (dot > 0 ? name.substring(dot) : ".mp4");
        Path mobile = parent.resolve(mobileName);
        return Files.isRegularFile(mobile) ? mobile : video;
    }

    /**
     * Starts decoding on a worker thread and hands the source to {@code sink}.
     * The sink may be called from any thread; keep the field volatile.
     */
    public static void startAsync(Path video, int width, int height, int fps,
                                  Path gameDir, Consumer<String> log,
                                  Consumer<VideoSource> sink) {
        Path sourceFile = mobileVariant(video);
        if (FfmpegPaths.isAndroid()) {
            log.accept("Android: using JCodec, source=" + sourceFile);
            sink.accept(JCodecVideoSource.start(sourceFile, width, height, fps, log));
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
                sink.accept(JCodecVideoSource.start(sourceFile, width, height, fps, log));
                log.accept("JCodec video started: " + width + "x" + height + "@" + fps
                        + " source=" + sourceFile);
            } catch (Throwable jt) {
                log.accept("JCodec video failed: " + jt);
            }
            return null;
        });
    }
}
