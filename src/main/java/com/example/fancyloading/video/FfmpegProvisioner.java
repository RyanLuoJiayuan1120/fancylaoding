package com.example.fancyloading.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a pinned FFmpeg static build on first use, verifies its SHA-256 and
 * extracts it under {@code <gameDir>/config/fancyloading/tools/}.
 *
 * <p>Nothing is bundled in the mod jar: the binaries are large, platform
 * specific and carry their own license. GitHub is the source of truth; a list
 * of GitHub acceleration prefixes is tried first for users who need it.</p>
 *
 * <p>This class only uses JDK APIs, so it is safe to call from the early Mixin
 * phase.</p>
 */
public final class FfmpegProvisioner {

    public static final String VERSION = "b6.1.1";
    private static final String BASE =
            "https://github.com/eugeneware/ffmpeg-static/releases/download/" + VERSION + "/";
    private static final long MAX_EXTRACTED = 256L * 1024 * 1024;
    private static final Object LOCK = new Object();

    /** GitHub acceleration prefixes, tried before the direct GitHub URL. */
    private static final List<String> DEFAULT_MIRRORS = List.of(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/"
    );

    private record Asset(String file, String license, String sha256, long size) {
    }

    private static final Map<String, Asset> ASSETS = Map.of(
            "linux-x64", new Asset("ffmpeg-linux-x64.gz", "linux-x64.LICENSE",
                    "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa", 29_354_986L),
            "linux-arm64", new Asset("ffmpeg-linux-arm64.gz", "linux-arm64.LICENSE",
                    "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0", 25_568_691L),
            "win32-x64", new Asset("ffmpeg-win32-x64.gz", "win32-x64.LICENSE",
                    "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77", 29_581_307L),
            "darwin-arm64", new Asset("ffmpeg-darwin-arm64.gz", "darwin-arm64.LICENSE",
                    "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa", 19_246_198L),
            "darwin-x64", new Asset("ffmpeg-darwin-x64.gz", "darwin-x64.LICENSE",
                    "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106", 25_296_431L)
    );

    private FfmpegProvisioner() {
    }

    /** Current OS/arch key, or null if this platform is not in the manifest. */
    public static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("win")) return "win32-x64";
        if (os.contains("mac") || os.contains("darwin")) {
            return aarch64 ? "darwin-arm64" : "darwin-x64";
        }
        if (os.contains("linux")) {
            return aarch64 ? "linux-arm64" : "linux-x64";
        }
        return null;
    }

    public static boolean isSupported() {
        return platformKey() != null;
    }

    /** Blocking variant; call from a daemon worker, never the render thread. */
    public static Path ensure(Path gameDir, Consumer<String> log) throws Exception {
        synchronized (LOCK) {
            String key = platformKey();
            if (key == null) {
                throw new IOException("unsupported platform: " + System.getProperty("os.name")
                        + "/" + System.getProperty("os.arch"));
            }
            Asset asset = ASSETS.get(key);
            if (asset == null) {
                throw new IOException("no FFmpeg asset for " + key);
            }

            Path toolRoot = FfmpegPaths.toolRoot(gameDir);
            Path dir = toolRoot.resolve("ffmpeg-" + VERSION + "-" + key);
            Path binary = dir.resolve(key.startsWith("win") ? "ffmpeg.exe" : "ffmpeg");
            Path marker = dir.resolve(".installed");
            Path failed = dir.resolve(".failed");

            if (FfmpegPaths.isAndroid()) {
                cleanupLegacy(gameDir, toolRoot, log);
                if (FfmpegPaths.androidFilesDir() == null) {
                    log.accept("FFmpeg WARNING: Android private files dir not found, falling back to "
                            + toolRoot + " (likely noexec)");
                }
            }

            if (Files.isRegularFile(failed) && System.getProperty("fancyloading.ffmpegRetry") == null) {
                throw new IOException("FFmpeg previously failed on this device, skipping download: "
                        + firstLine(Files.readString(failed)));
            }
            Files.deleteIfExists(failed);

            if (Files.isRegularFile(binary) && Files.isRegularFile(marker)
                    && markerContains(marker, asset.sha256) && smokeTest(binary, log)) {
                log.accept("FFmpeg ready: " + binary);
                return binary;
            }

            Files.createDirectories(dir);
            log.accept("FFmpeg tool dir: " + dir
                    + (FfmpegPaths.isAndroid() ? " (Android: " + FfmpegPaths.androidDiagnostics() + ")" : ""));
            Path gz = dir.resolve(asset.file + ".part");
            Path out = dir.resolve(binary.getFileName() + ".part");
            Files.deleteIfExists(gz);
            Files.deleteIfExists(out);
            Files.deleteIfExists(marker);

            download(asset, gz, log);
            log.accept("FFmpeg verifying SHA-256...");
            String actual = sha256(gz);
            if (!actual.equalsIgnoreCase(asset.sha256)) {
                Files.deleteIfExists(gz);
                throw new IOException("FFmpeg SHA-256 mismatch: " + actual);
            }
            log.accept("FFmpeg extracting to " + out + " ...");
            gunzip(gz, out);
            Files.deleteIfExists(gz);
            makeExecutable(out);
            long size = Files.size(out);
            log.accept("FFmpeg extracted " + size + " bytes, running -version smoke test...");
            if (!smokeTest(out, log)) {
                Files.deleteIfExists(out);
                try {
                    Files.createDirectories(dir);
                    Files.writeString(failed, "version=" + VERSION
                            + "\npath=" + out + "\nreason=exec smoke test failed (see log above)\n");
                } catch (IOException ignored) {
                }
                throw new IOException("downloaded FFmpeg failed `-version` smoke test at " + out
                        + " (exec from " + toolRoot + " is not permitted on this device?)");
            }
            move(out, binary);
            makeExecutable(binary);
            downloadLicense(asset, dir.resolve("LICENSE"), log);
            Files.writeString(marker, "version=" + VERSION + "\nsha256=" + asset.sha256 + "\n");
            Files.deleteIfExists(failed);
            log.accept("FFmpeg installed: " + binary);
            return binary;
        }
    }

    /** Non-blocking variant; returns a future that completes on a daemon thread. */
    public static CompletableFuture<Path> ensureAsync(Path gameDir, Consumer<String> log) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(ensure(gameDir, log));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "fancyloading-ffmpeg");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    private static void download(Asset asset, Path target, Consumer<String> log) throws Exception {
        HttpClient client = httpClient();
        Exception last = null;
        for (String prefix : mirrors()) {
            String url = prefix + BASE + asset.file;
            log.accept("FFmpeg download: " + url);
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(6))
                        .header("User-Agent", "fancyloading/" + VERSION)
                        .GET()
                        .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    last = new IOException("HTTP " + response.statusCode() + " from " + url);
                    continue;
                }
                try (InputStream in = response.body();
                     OutputStream file = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    long lastLog = 0;
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        file.write(buffer, 0, n);
                        total += n;
                        long now = System.nanoTime();
                        if (now - lastLog > 2_000_000_000L) {
                            lastLog = now;
                            log.accept("FFmpeg download " + (total / 1024 / 1024) + " MB");
                        }
                    }
                }
                if (Files.size(target) != asset.size) {
                    last = new IOException("size mismatch: " + Files.size(target)
                            + " != " + asset.size);
                    Files.deleteIfExists(target);
                    continue;
                }
                log.accept("FFmpeg downloaded: " + Files.size(target) + " bytes");
                return;
            } catch (Exception e) {
                last = e;
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
            }
        }
        if (downloadExternal(asset, target, log)) {
            return;
        }
        throw new IOException("all FFmpeg mirrors failed", last);
    }

    private static HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30));
        SSLContext context = trustStoreContext();
        if (context != null) {
            builder.sslContext(context);
        }
        return builder.build();
    }

    /** Some JREs in this environment resolve an empty default trust store. */
    private static SSLContext trustStoreContext() {
        try {
            Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
            if (!Files.isRegularFile(cacerts)) return null;
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = Files.newInputStream(cacerts)) {
                store.load(in, "changeit".toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Last resort when the JDK HTTP stack cannot validate TLS (broken cacerts). */
    private static boolean downloadExternal(Asset asset, Path target, Consumer<String> log) {
        for (String prefix : mirrors()) {
            String url = prefix + BASE + asset.file;
            for (String command : new String[]{"curl", "curl.exe", "wget"}) {
                try {
                    ProcessBuilder pb = command.startsWith("wget")
                            ? new ProcessBuilder(command, "-q", "-O", target.toString(), url)
                            : new ProcessBuilder(command, "-L", "--fail", "--connect-timeout", "30",
                                    "--max-time", "360", "-o", target.toString(), url);
                    pb.redirectErrorStream(true);
                    log.accept("FFmpeg external download: " + command + " " + url);
                    Process process = pb.start();
                    try (InputStream in = process.getInputStream()) {
                        in.transferTo(OutputStream.nullOutputStream());
                    }
                    if (!process.waitFor(6, TimeUnit.MINUTES) || process.exitValue() != 0) {
                        Files.deleteIfExists(target);
                        continue;
                    }
                    if (Files.size(target) == asset.size) {
                        return true;
                    }
                    Files.deleteIfExists(target);
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private static void downloadLicense(Asset asset, Path target, Consumer<String> log) {
        try {
            HttpClient client = httpClient();
            for (String prefix : mirrors()) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(prefix + BASE + asset.license))
                            .timeout(Duration.ofSeconds(30))
                            .GET().build();
                    HttpResponse<byte[]> response =
                            client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        Files.write(target, response.body());
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.accept("FFmpeg license download skipped: " + e.getMessage());
        }
    }

    private static List<String> mirrors() {
        return DEFAULT_MIRRORS;
    }

    private static void gunzip(Path gz, Path out) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz));
             OutputStream file = Files.newOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > MAX_EXTRACTED) {
                    throw new IOException("FFmpeg archive expands beyond " + MAX_EXTRACTED + " bytes");
                }
                file.write(buffer, 0, n);
            }
        }
    }

    private static boolean smokeTest(Path binary, Consumer<String> log) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            // Wait with a hard timeout; reading stdout to EOF first would block
            // forever if a binary hangs without closing its pipes.
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (head.size() < 4096) head.write(buffer, 0, Math.min(n, 4096 - head.size()));
                }
            }
            String out = head.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (!finished) {
                log.accept("FFmpeg smoke test timed out after 20s: " + out.trim());
                return false;
            }
            boolean ok = process.exitValue() == 0;
            if (!ok) {
                log.accept("FFmpeg smoke test failed (exit=" + process.exitValue() + "): " + out.trim());
            }
            return ok;
        } catch (Exception e) {
            log.accept("FFmpeg smoke test could not start " + binary + ": " + e);
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception ignored) {
            // Windows / non-POSIX filesystem
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String firstLine(String text) {
        int i = text.indexOf('\n');
        return (i < 0 ? text : text.substring(0, i)).trim();
    }

    /**
     * Older builds put the binary on /storage/emulated (noexec). Remove that
     * stale copy once we know we can use the app-private directory instead.
     */
    private static void cleanupLegacy(Path gameDir, Path toolRoot, Consumer<String> log) {
        try {
            Path legacy = gameDir.resolve("config").resolve("fancyloading").resolve("tools")
                    .toAbsolutePath().normalize();
            Path current = toolRoot.toAbsolutePath().normalize();
            if (legacy.equals(current) || !Files.isDirectory(legacy) || !legacy.startsWith(gameDir)) {
                return;
            }
            try (var stream = Files.walk(legacy)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            log.accept("FFmpeg removed stale sdcard tools: " + legacy);
        } catch (Exception e) {
            log.accept("FFmpeg stale-tools cleanup skipped: " + e);
        }
    }

    private static boolean markerContains(Path marker, String sha) {
        try {
            return Files.readString(marker).contains(sha);
        } catch (IOException e) {
            return false;
        }
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
