package com.glowingmushroom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-updater. Looks at this project's own "latest-build" GitHub release (HTTPS only), and if a newer
 * jar is there downloads it next to the current one in the mods folder. Safety checks before it is kept:
 * the file must match the SHA-256 GitHub publishes for the asset, be a reasonable size, be a zip with a
 * fabric.mod.json whose id is this mod, and have a newer version than the one running. The new jar
 * takes effect after a game restart; older copies are deleted on the next start.
 */
public final class Updater {
    private Updater() {}

    private static final String MOD_ID = "glowingmushroomauto";
    private static final String RELEASE_API = "https://api.github.com/repos/Ilikebiscoff/glowing-mushroom-/releases/tags/latest-build";
    private static final Pattern JAR_NAME = Pattern.compile("^glowing-mushroom-auto-(\\d+(?:\\.\\d+)*)\\.jar$");
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long CHECK_EVERY_MS = 60 * 60_000;

    public static volatile boolean auto = true;
    /** Version that was downloaded this session and waits for a restart (null = none). */
    public static volatile String pendingRestart;
    private static volatile boolean busy;
    private static long lastCheck;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Call every tick; checks about once an hour when auto-update is on. */
    public static void tick() {
        if (!auto || busy || pendingRestart != null) return;
        long now = System.currentTimeMillis();
        if (lastCheck == 0 || now - lastCheck > CHECK_EVERY_MS) {
            lastCheck = now;
            check(false);
        }
    }

    /** Checks for (and downloads) an update in the background. {@code verbose}: also report "up to date" / errors. */
    public static void check(boolean verbose) {
        if (busy) {
            if (verbose) Chat.msg("An update check is already running.");
            return;
        }
        busy = true;
        Thread t = new Thread(() -> {
            try {
                run(verbose);
            } catch (Exception e) {
                if (verbose) say("Update check failed: " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "glowing-updater");
        t.setDaemon(true);
        t.start();
    }

    private static void run(boolean verbose) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASE_API))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("GitHub answered " + resp.statusCode());
        JsonArray assets = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonArray("assets");

        JsonObject best = null;
        String bestVer = null;
        for (var el : assets) {
            JsonObject a = el.getAsJsonObject();
            Matcher m = JAR_NAME.matcher(a.get("name").getAsString());
            if (m.matches() && (bestVer == null || compare(m.group(1), bestVer) > 0)) {
                best = a;
                bestVer = m.group(1);
            }
        }
        String cur = currentVersion();
        if (best == null) throw new IllegalStateException("no jar in the release");
        if (compare(bestVer, cur) <= 0) {
            if (verbose) say("Up to date (" + cur + ").");
            return;
        }
        if (pendingRestart != null && compare(bestVer, pendingRestart) <= 0) return;

        long size = best.get("size").getAsLong();
        String url = best.get("browser_download_url").getAsString();
        String digest = best.has("digest") && !best.get("digest").isJsonNull() ? best.get("digest").getAsString() : null;
        if (size <= 0 || size > MAX_BYTES) throw new IllegalStateException("unexpected file size " + size);
        if (!url.startsWith("https://github.com/Ilikebiscoff/glowing-mushroom-/releases/download/"))
            throw new IllegalStateException("unexpected download url");
        if (digest == null || !digest.startsWith("sha256:"))
            throw new IllegalStateException("release has no SHA-256 to verify against");

        say("Downloading update " + bestVer + " (you have " + cur + ")...");
        Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
        Path tmp = mods.resolve(".glowing-update.tmp");
        Path target = mods.resolve(best.get("name").getAsString());
        try {
            HttpResponse<InputStream> dl = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (dl.statusCode() != 200) throw new IllegalStateException("download answered " + dl.statusCode());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = dl.body(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalStateException("download too large");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String got = hex(sha.digest());
            if (!got.equalsIgnoreCase(digest.substring("sha256:".length())))
                throw new IllegalStateException("SHA-256 mismatch - not installing");
            verifyJar(tmp, bestVer);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        pendingRestart = bestVer;
        say("Update " + bestVer + " downloaded and verified. Restart the game to use it (the old jar is removed on the next start).");
    }

    /** Must be a zip containing a fabric.mod.json whose id is this mod and whose version is what we expect. */
    private static void verifyJar(Path jar, String expectVersion) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) throw new IllegalStateException("not a Fabric mod");
            JsonObject o;
            try (InputStream in = z.getInputStream(e)) {
                o = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (!MOD_ID.equals(o.get("id").getAsString())) throw new IllegalStateException("jar is a different mod");
            if (!o.get("version").getAsString().equals(expectVersion)) throw new IllegalStateException("version mismatch inside jar");
        }
    }

    /**
     * Run once at startup: delete older copies of this mod from the mods folder (the loaded one is the newest,
     * the old ones are not locked, so this also works on Windows).
     */
    public static void cleanupOldJars() {
        try {
            Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
            String cur = currentVersion();
            try (Stream<Path> files = Files.list(mods)) {
                files.forEach(f -> {
                    Matcher m = JAR_NAME.matcher(f.getFileName().toString());
                    if (m.matches() && compare(m.group(1), cur) < 0) {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
            Files.deleteIfExists(mods.resolve(".glowing-update.tmp"));
        } catch (Exception ignored) {
        }
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0, yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Chat from a background thread. */
    private static void say(String s) {
        net.minecraft.client.Minecraft.getInstance().execute(() -> Chat.msg(s));
    }
}
