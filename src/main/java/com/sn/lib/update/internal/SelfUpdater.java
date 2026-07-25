package com.sn.lib.update.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.security.CodeSource;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.hook.SemverComparator;
import com.sn.lib.reload.Reloadable;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.text.SnText;
import com.sn.lib.yml.SnYml;

/**
 * Self-updater of SnLib.jar itself, owned exclusively by the SnLib bootstrap.
 *
 * <p>Scope boundary, and the reason this lives outside {@code com.sn.lib.update}: the
 * {@code sn.updates()} module every consumer reaches stays STRICTLY notify-only and is not
 * touched by this class. This component is internal to the library bootstrap, is never
 * exposed through {@link Sn}, and only ever acts on SnLib's own jar. No consumer plugin can
 * reach it and no consumer jar is ever downloaded, moved or deleted by it.</p>
 *
 * <p>What it does on each pass, always off the main thread: polls SnLib's own public GitHub
 * repository for {@code releases/latest}, and when a strictly newer release exists (by
 * default only within the installed major version) it downloads the release asset,
 * verifies it against the SHA-256 digest published by the GitHub API and against the
 * {@code plugin.yml} inside the downloaded jar, then swaps the file on disk: the new jar is
 * moved into the plugins folder and the old one is deleted.</p>
 *
 * <p>It NEVER swaps classes at runtime. The running server keeps the classes it loaded at
 * boot; the downloaded version only becomes active on the next full server restart. That is
 * the whole point of the feature: the jar is already in place before the next scheduled
 * restart, so no manual step sits between a published release and an updated server.</p>
 *
 * <p>Install order is deliberate: the new jar is moved in FIRST and the old one deleted
 * after. A crash between the two steps leaves two SnLib jars, which Bukkit resolves with an
 * "ambiguous plugin name" warning while still booting; the reverse order would risk leaving
 * the server with NO SnLib, which would take down every consumer. On Linux the swap is safe
 * while the server runs because unlinking an open file keeps the inode alive for the JVM's
 * open descriptor. When the filesystem refuses the swap (a Windows file lock), the verified
 * jar is staged into Bukkit's own update folder instead and the server applies it at the
 * next boot.</p>
 */
public final class SelfUpdater implements Reloadable {

    private static final String REPO = "ValentinTarnovsky/SnLib";
    private static final String LATEST_ENDPOINT =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Hard allowlist: a release asset may only ever be fetched from SnLib's own repo. */
    private static final String ASSET_URL_PREFIX =
            "https://github.com/" + REPO + "/releases/download/";
    private static final String USER_AGENT = "SnLib-SelfUpdater";
    private static final String PERMISSION = "snlib.admin.update";
    private static final String STAGING_DIR = ".snlib-update";
    private static final String PART_SUFFIX = ".part";

    private static final String KEY_ENABLED = "auto-update.enabled";
    private static final String KEY_INTERVAL = "auto-update.interval-hours";
    private static final String KEY_SAME_MAJOR = "auto-update.same-major-only";

    private static final int DEFAULT_INTERVAL_HOURS = 12;
    private static final int MIN_INTERVAL_HOURS = 1;
    private static final int MAX_INTERVAL_HOURS = 168;
    private static final long TICKS_PER_HOUR = 72000L;
    private static final long INITIAL_DELAY_TICKS = 2400L;
    private static final long NOTIFY_DELAY_TICKS = 40L;

    private static final long MAX_ASSET_BYTES = 128L * 1024L * 1024L;
    private static final long MIN_ASSET_BYTES = 1024L * 1024L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Server-wide static justified: SnLib is a singleton bootstrap, so there is exactly one
     * self-updater per server. The shared join listener reads the pending version from here
     * and the field is cleared on disable.
     */
    private static volatile @Nullable SelfUpdater active;

    private final Sn ctx;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final Object clientLock = new Object();
    private volatile @Nullable HttpClient client;
    private volatile @Nullable TaskHandle handle;
    private volatile int armedIntervalHours;
    private volatile @Nullable String pendingVersion;
    private volatile @Nullable String latestSeen;

    public SelfUpdater(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Shared PlayerJoinEvent listener inscribed in the ListenerHub, so an admin joining
     * after an update was installed still learns that a restart is pending.
     */
    public static Listener joinListener() {
        return new JoinListener();
    }

    /**
     * Running self-updater, or null while SnLib is disabled. Exposed here rather than on
     * the bootstrap so the whole feature stays inside this internal package and adds no
     * public surface to any consumer-visible class.
     */
    public static @Nullable SelfUpdater active() {
        return active;
    }

    /**
     * Arms the recurring check and registers the reload hook. The first pass runs two
     * minutes after enable, deliberately after the notify-only update checker's own first
     * pass at sixty seconds, so the console reads "version available" before "installed".
     */
    public void arm() {
        active = this;
        ctx.reload().register(this);
        purgeStaging();
        armTimer(intervalHours());
    }

    /** Idempotent teardown: cancels the timer and releases the HTTP client. */
    public void shutdown() {
        TaskHandle current = handle;
        handle = null;
        if (current != null) {
            current.cancel();
        }
        HttpClient http = client;
        client = null;
        if (http != null) {
            http.shutdown();
        }
        if (active == this) {
            active = null;
        }
    }

    /**
     * Re-arms the timer when {@code auto-update.interval-hours} changed. The enabled and
     * same-major gates are re-read on every pass instead, so they need no re-arm.
     */
    @Override
    public void reload() {
        int desired = intervalHours();
        if (desired != armedIntervalHours) {
            armTimer(desired);
        }
    }

    /**
     * Runs one immediate pass on behalf of {@code /snlib update}, reporting the outcome
     * back to the sender. Failures are always reported on this path, never warn-once.
     */
    public void checkNow(CommandSender sender) {
        report(sender, "&7Checking &f" + REPO + "&7 for a newer SnLib...");
        ctx.scheduler().async(() -> check(sender));
    }

    /** Version already installed on disk and waiting for a restart, or null. */
    public @Nullable String pendingVersion() {
        return pendingVersion;
    }

    /** Latest version seen on the last successful poll, or null if never polled. */
    public @Nullable String latestSeen() {
        return latestSeen;
    }

    /** True when {@code auto-update.enabled} is currently on. */
    public boolean isEnabled() {
        return config().getBoolean(KEY_ENABLED, true);
    }

    /** Configured hours between passes, clamped to the supported range. */
    public int intervalHours() {
        int raw = config().getInt(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS);
        return Math.max(MIN_INTERVAL_HOURS, Math.min(MAX_INTERVAL_HOURS, raw));
    }

    private void armTimer(int hours) {
        TaskHandle previous = handle;
        if (previous != null) {
            previous.cancel();
        }
        armedIntervalHours = hours;
        handle = ctx.scheduler().timerAsync(INITIAL_DELAY_TICKS, hours * TICKS_PER_HOUR,
                () -> check(null));
    }

    /**
     * SnLib's own main config. Never null and never throwing: the bootstrap's self spec
     * always declares {@code .config("config.yml")}. Re-read on every access so a
     * {@code /snlib reload} applies without a restart.
     */
    private SnYml config() {
        return ctx.yml().config();
    }

    /**
     * One pass. {@code sender} is null for the timer path (failures WARN once per reason
     * per enable) and non-null for the manual path (every outcome is reported).
     */
    private void check(@Nullable CommandSender sender) {
        if (ctx.isShuttingDown()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            report(sender, "&7A self-update check is already running; ignoring this one.");
            return;
        }
        try {
            runPass(sender);
        } catch (RuntimeException unexpected) {
            fail(sender, "self-update failed: " + unexpected, "unexpected");
        } finally {
            running.set(false);
        }
    }

    private void runPass(@Nullable CommandSender sender) {
        if (!isEnabled()) {
            report(sender, "&7SnLib auto-update is &cdisabled&7 in config.yml.");
            return;
        }
        String current = ctx.plugin().getPluginMeta().getVersion();
        String body = fetch(LATEST_ENDPOINT, sender);
        if (body == null) {
            return;
        }
        String tag = jsonString(body, "tag_name");
        if (tag == null) {
            fail(sender, "self-update failed: release response without tag_name", "no-tag");
            return;
        }
        String latest = stripTagPrefix(tag);
        latestSeen = latest;

        String staged = pendingVersion;
        if (staged != null && SemverComparator.compareVersions(latest, staged) <= 0) {
            report(sender, "&eSnLib &f" + staged + "&e is already installed on disk;"
                    + " restart the server to activate it.");
            return;
        }
        if (SemverComparator.compareVersions(latest, current) <= 0) {
            report(sender, "&7SnLib is up to date (&f" + current + "&7).");
            return;
        }
        boolean sameMajorOnly = config().getBoolean(KEY_SAME_MAJOR, true);
        if (sameMajorOnly && !isSameMajor(latest, current)) {
            String message = "SnLib " + latest + " is available but crosses a major version"
                    + " (installed " + current + "); install it manually.";
            if (sender == null) {
                warnOnce("major:" + latest, message);
            } else {
                report(sender, "&e" + message);
            }
            return;
        }

        String assets = assetsSlice(body);
        String assetUrl = assets == null ? null : jsonString(assets, "browser_download_url");
        if (assetUrl == null || !isAllowedAssetUrl(assetUrl)) {
            fail(sender, "self-update failed: release " + latest
                    + " has no asset from " + ASSET_URL_PREFIX, "asset-url:" + latest);
            return;
        }
        String expectedSha = expectedSha256(jsonString(assets, "digest"));

        File runningJar = ownJar();
        if (runningJar == null) {
            fail(sender, "self-update failed: cannot resolve the running SnLib.jar on disk",
                    "own-jar");
            return;
        }
        install(runningJar, latest, current, assetUrl, expectedSha, sender);
    }

    /**
     * Downloads, verifies and swaps the jar. Any verification failure deletes the partial
     * download and aborts without touching the installed jar.
     */
    private void install(File runningJar, String latest, String current, String assetUrl,
            @Nullable String expectedSha, @Nullable CommandSender sender) {
        Path pluginsDir = runningJar.toPath().toAbsolutePath().getParent();
        if (pluginsDir == null) {
            fail(sender, "self-update failed: the running jar has no parent directory", "no-parent");
            return;
        }
        Path part = pluginsDir.resolve(STAGING_DIR).resolve("SnLib-" + latest + ".jar" + PART_SUFFIX);
        try {
            Files.createDirectories(part.getParent());
            long size = download(assetUrl, part);
            if (size < MIN_ASSET_BYTES) {
                throw new IOException("downloaded asset is only " + size + " bytes");
            }
            if (expectedSha != null) {
                String actual = sha256(part);
                if (!expectedSha.equalsIgnoreCase(actual)) {
                    throw new IOException("SHA-256 mismatch: expected " + expectedSha
                            + ", got " + actual);
                }
            } else {
                ctx.plugin().getLogger().info("Release " + latest + " publishes no asset digest;"
                        + " verifying the download by its plugin.yml only.");
            }
            String problem = verifyJar(part, latest);
            if (problem != null) {
                throw new IOException(problem);
            }
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            deleteQuietly(part);
            fail(sender, "self-update failed: " + failure.getMessage(), "download:" + latest);
            return;
        }

        Path target = pluginsDir.resolve(targetFileName(runningJar.getName(), current, latest));
        Path currentPath = runningJar.toPath().toAbsolutePath();
        try {
            move(part, target);
        } catch (IOException locked) {
            stageForRestart(part, runningJar, latest, current, locked, sender);
            return;
        }
        if (!target.equals(currentPath)) {
            try {
                Files.deleteIfExists(currentPath);
            } catch (IOException undeletable) {
                rollback(target, part, runningJar, latest, current, undeletable, sender);
                return;
            }
        }
        pendingVersion = latest;
        announce(latest, current, "installed on disk", sender);
    }

    /**
     * The new jar landed under a new name but the old one could not be deleted, which
     * happens on Windows where the running jar stays locked. Leaving both would let the
     * server boot on the WRONG one (Bukkit resolves the duplicate name arbitrarily), so the
     * new file is pulled back out of the plugins folder and handed to the update folder
     * instead. If even the pull-back fails the duplicate is real and must be shouted about.
     */
    private void rollback(Path installed, Path part, File runningJar, String latest,
            String current, IOException cause, @Nullable CommandSender sender) {
        try {
            move(installed, part);
        } catch (IOException stuck) {
            pendingVersion = latest;
            ctx.plugin().getLogger().severe("SnLib " + latest + " was installed as "
                    + installed.getFileName() + " but the previous " + runningJar.getName()
                    + " could not be removed (" + cause.getMessage() + "). Delete it by hand"
                    + " before restarting: two SnLib jars in plugins/ make the server pick"
                    + " one at random.");
            return;
        }
        stageForRestart(part, runningJar, latest, current, cause, sender);
    }

    /**
     * Fallback when the filesystem refuses to replace the running jar (Windows keeps an
     * open jar locked): hands the verified file to Bukkit's own update folder, which the
     * server applies over the installed jar at the next boot.
     */
    private void stageForRestart(Path part, File runningJar, String latest, String current,
            IOException cause, @Nullable CommandSender sender) {
        try {
            Path updateDir = Bukkit.getUpdateFolderFile().toPath();
            Files.createDirectories(updateDir);
            Files.move(part, updateDir.resolve(runningJar.getName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException staging) {
            deleteQuietly(part);
            fail(sender, "self-update failed: cannot replace the jar (" + cause.getMessage()
                    + ") and cannot stage it either (" + staging.getMessage() + ")",
                    "stage:" + latest);
            return;
        }
        pendingVersion = latest;
        announce(latest, current, "staged in the update folder", sender);
    }

    /** Console INFO plus a chat notice to online admins; joiners are covered by the listener. */
    private void announce(String latest, String current, String what,
            @Nullable CommandSender sender) {
        ctx.plugin().getLogger().info("SnLib " + latest + " " + what
                + "; restart the server to activate it (running " + current + ").");
        Component notice = notice(latest, current);
        ctx.scheduler().sync(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(PERMISSION)) {
                    player.sendMessage(notice);
                }
            }
        });
        boolean alreadyBroadcast = sender instanceof Player player
                && player.hasPermission(PERMISSION);
        if (!alreadyBroadcast) {
            report(sender, "&aSnLib &f" + latest + " &a" + what
                    + ". Restart the server to activate it.");
        }
    }

    private static Component notice(String latest, String current) {
        return SnText.color("&eSnLib &a" + latest + " &7is installed on disk (running &c"
                + current + "&7). &7Restart the server to activate it.");
    }

    private @Nullable String fetch(String endpoint, @Nullable CommandSender sender) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(API_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                fail(sender, "self-update check failed: HTTP " + response.statusCode(),
                        "http:" + response.statusCode());
                return null;
            }
            return response.body();
        } catch (IOException e) {
            fail(sender, "self-update check failed: " + e, "io");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(sender, "self-update check failed: interrupted", "interrupted");
            return null;
        }
    }

    /**
     * Streams the asset to {@code target}, enforcing a hard size cap so a hostile or broken
     * response can never fill the disk. Returns the number of bytes written.
     */
    private long download(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response =
                client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("asset download returned HTTP " + response.statusCode());
        }
        long total = 0L;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ASSET_BYTES) {
                    throw new IOException("asset exceeds the " + MAX_ASSET_BYTES + " byte cap");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /**
     * Atomic replace when the filesystem supports it, falling back to a plain replacing
     * move. Both throw when the target is locked, which is the Windows path handled by the
     * caller.
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Drops leftover partial downloads from a previous run. */
    private void purgeStaging() {
        File jar = ownJar();
        Path parent = jar == null ? null : jar.toPath().toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        Path staging = parent.resolve(STAGING_DIR);
        if (!Files.isDirectory(staging)) {
            return;
        }
        try (Stream<Path> files = Files.list(staging)) {
            files.filter(path -> path.getFileName().toString().endsWith(PART_SUFFIX))
                    .forEach(SelfUpdater::deleteQuietly);
        } catch (IOException ignored) {
            // A staging folder we cannot list is not worth a warning; the next pass rewrites it.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: a leftover .part is harmless, it is never loaded by the server.
        }
    }

    /** One WARN per reason per enable on the timer path; always reported on the manual path. */
    private void fail(@Nullable CommandSender sender, String message, String reasonKey) {
        if (sender == null) {
            warnOnce(reasonKey, message);
            return;
        }
        ctx.plugin().getLogger().warning(message);
        report(sender, "&c" + message);
    }

    private void warnOnce(String reasonKey, String message) {
        if (warned.add(reasonKey)) {
            ctx.plugin().getLogger().warning(message);
        }
    }

    /** Sends one line to a command sender from any thread, hopping to the main thread. */
    private void report(@Nullable CommandSender sender, String text) {
        if (sender == null) {
            return;
        }
        Component line = SnText.color(text);
        ctx.scheduler().sync(() -> sender.sendMessage(line));
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (clientLock) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * Resolves SnLib's own jar from the code source of this class. Same primitive the GUI
     * seeder uses to reach a consumer jar, pointed at the library instead.
     */
    private @Nullable File ownJar() {
        ProtectionDomain domain = ctx.plugin().getClass().getProtectionDomain();
        if (domain == null) {
            return null;
        }
        CodeSource source = domain.getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        URL location = source.getLocation();
        try {
            File file = new File(location.toURI());
            return file.isFile() ? file : null;
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Pure helpers, package-private for the unit tests.
    // ---------------------------------------------------------------------------------

    /**
     * True only for a release asset served by SnLib's own repository over HTTPS. The URL
     * checked here is the one published by the API, never the redirect target: GitHub
     * redirects downloads to its object storage, and validating the post-redirect host
     * would defeat the point.
     */
    static boolean isAllowedAssetUrl(@Nullable String url) {
        return url != null && url.startsWith(ASSET_URL_PREFIX);
    }

    /** Leading numeric segment of a version, or null when it does not start with a digit. */
    static @Nullable String majorOf(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? null : trimmed.substring(0, end);
    }

    /** True when both versions share a parseable major segment. */
    static boolean isSameMajor(@Nullable String a, @Nullable String b) {
        String left = majorOf(a);
        String right = majorOf(b);
        return left != null && left.equals(right);
    }

    /**
     * Target file name of the swap: a versioned jar keeps its convention with the new
     * version substituted, and any other name (the documented plain {@code SnLib.jar}
     * included) is preserved as-is so the swap replaces it in place.
     */
    static String targetFileName(String runningName, @Nullable String currentVersion,
            String newVersion) {
        if (currentVersion == null || currentVersion.isEmpty()
                || !runningName.contains(currentVersion)) {
            return runningName;
        }
        return runningName.replace(currentVersion, newVersion);
    }

    /** Normalizes the API digest field ({@code sha256:<hex>}) to a bare lowercase hex. */
    static @Nullable String expectedSha256(@Nullable String digest) {
        if (digest == null) {
            return null;
        }
        String trimmed = digest.trim();
        int colon = trimmed.indexOf(':');
        String hex = colon < 0 ? trimmed : trimmed.substring(colon + 1);
        if (colon >= 0 && !trimmed.regionMatches(true, 0, "sha256", 0, 6)) {
            return null;
        }
        return hex.length() == 64 ? hex.toLowerCase(Locale.ROOT) : null;
    }

    /** Lowercase hex SHA-256 of a file. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable on this JVM", impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Structural check of a downloaded jar: it must be a readable archive whose plugin.yml
     * declares SnLib, the expected version and the library's own main class. Returns null
     * when valid, otherwise the reason it was rejected.
     */
    static @Nullable String verifyJar(Path jar, String expectedVersion) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry("plugin.yml");
            if (entry == null) {
                return "downloaded jar has no plugin.yml";
            }
            String descriptor;
            try (InputStream in = archive.getInputStream(entry)) {
                descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String name = yamlValue(descriptor, "name");
            String version = yamlValue(descriptor, "version");
            String main = yamlValue(descriptor, "main");
            if (!"SnLib".equals(name)) {
                return "downloaded jar declares plugin name '" + name + "'";
            }
            if (!"com.sn.lib.SnLibPlugin".equals(main)) {
                return "downloaded jar declares main class '" + main + "'";
            }
            if (!expectedVersion.equals(version)) {
                return "downloaded jar is version '" + version + "', expected " + expectedVersion;
            }
            return null;
        } catch (IOException notAJar) {
            return "downloaded file is not a readable jar: " + notAJar.getMessage();
        }
    }

    /**
     * Value of a top-level {@code key: value} line of a plugin descriptor. Deliberately a
     * line scan and not a YAML parse: the three keys checked here are always plain
     * top-level scalars, and this runs before the file is trusted.
     */
    static @Nullable String yamlValue(String descriptor, String key) {
        for (String line : descriptor.split("\\R")) {
            if (!line.startsWith(key + ":")) {
                continue;
            }
            String value = line.substring(key.length() + 1).trim();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /**
     * Slice of the {@code "assets": [ ... ]} array of a release payload, so asset fields are
     * only ever read from inside it and never from the release body text. Returns null when
     * the release publishes no asset array.
     */
    static @Nullable String assetsSlice(String body) {
        int at = body.indexOf("\"assets\"");
        if (at < 0) {
            return null;
        }
        int open = body.indexOf('[', at + "\"assets\"".length());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return body.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * First string value of {@code "field"} in a JSON body, scanned by hand. Duplicated
     * from the notify-only checker rather than shared, following the same self-contained
     * precedent as the player lookup utility: this component must not depend on the
     * internals of the module whose notify-only contract it deliberately stays outside of.
     */
    static @Nullable String jsonString(@Nullable String body, @Nullable String field) {
        if (body == null || field == null) {
            return null;
        }
        int at = body.indexOf('"' + field + '"');
        if (at < 0) {
            return null;
        }
        int i = at + field.length() + 2;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != ':') {
            return null;
        }
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder(32);
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (i + 1 >= body.length()) {
                    return null;
                }
                char next = body.charAt(i + 1);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(c).append(next);
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return null;
    }

    /** Trims the tag and strips a leading {@code v}/{@code V} only when a digit follows. */
    static String stripTagPrefix(String tag) {
        String trimmed = tag.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && trimmed.charAt(1) >= '0' && trimmed.charAt(1) <= '9') {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    /** Notifies joining admins that a downloaded version is waiting for a restart. */
    private static final class JoinListener implements Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            SelfUpdater updater = active;
            if (updater == null) {
                return;
            }
            String pending = updater.pendingVersion;
            if (pending == null) {
                return;
            }
            Player player = event.getPlayer();
            if (!player.hasPermission(PERMISSION)) {
                return;
            }
            String current = updater.ctx.plugin().getPluginMeta().getVersion();
            updater.ctx.scheduler().syncLater(NOTIFY_DELAY_TICKS, () -> {
                if (player.isOnline()) {
                    player.sendMessage(notice(pending, current));
                }
            });
        }
    }
}
