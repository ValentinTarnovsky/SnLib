package com.sn.lib.lang;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.text.SnText;
import com.sn.lib.yml.SnYml;
import com.sn.lib.yml.YamlPreprocessor;
import com.sn.lib.yml.YamlUpdater;

/**
 * Language module of a consumer context, reached through {@code sn.lang()}.
 *
 * <p>Files live under {@code lang/} in the consumer data folder. On every load the module
 * seeds {@code lang/messages_en.yml} from the consumer jar (always-merged afterwards
 * through {@link YamlUpdater}, gated by {@code update-configs}) and then merges the
 * {@code snlib-messages.yml} resource bundled inside SnLib.jar into that disk file, so
 * every consumer always has the shared {@code snlib.*} keys. Lang files carry NO version
 * marker: merging is structural and always-on. Translations ({@code lang} config key other
 * than {@code en}) are merged against the DISK {@code messages_en.yml} as reference, so
 * they also receive the {@code snlib.*} keys and any consumer key added later.</p>
 *
 * <p>Fallback: a key absent from the active language falls back to English with one WARN
 * per key; a key absent from English renders as {@code <missing:key>}. Single-line
 * messages sent through {@link #send} and {@link #broadcast} get the optional top-level
 * {@code prefix} value prepended, list values are sent line by line as-is. A leading
 * {@code [noprefix]} tag opts a single-line value out of the prefix; the tag itself is
 * stripped by the SnText render.</p>
 *
 * <p>Caching: lines without placeholder tokens are pre-rendered to {@link Component} at
 * load (including {@code [rgb]} interpolation, paid once); dynamic lines keep the raw
 * template and render per call through the fixed SnText pipeline (locals, then PAPI per
 * viewer, then colors). Synchronous I/O by design: loading and merging run only in
 * onEnable and in the reload command, never during gameplay.</p>
 */
public final class SnLang {

    private static final String LANG_DIR = "lang";
    private static final String FALLBACK_CODE = "en";
    private static final String CONSUMER_RESOURCE = LANG_DIR + "/messages_" + FALLBACK_CODE + ".yml";
    private static final String SNLIB_RESOURCE = "snlib-messages.yml";
    /** Re-send period of a held action bar; the vanilla bar fades after roughly 2-3s. */
    private static final long ACTIONBAR_REFRESH_TICKS = 40L;
    /** The prefix placeholder SnLib prepends automatically; embedding it in a value renders it literally. */
    static final String LITERAL_PREFIX_TOKEN = "{prefix}";
    /** Leading tag that opts a single-line value out of the configured prefix. */
    static final String NOPREFIX_TAG = "[noprefix]";
    /** MiniMessage interactive tag markers; losing one silently turns a chat button inert. */
    static final String[] INTERACTIVE_MARKERS = {"<click:", "<hover:"};

    private final Sn ctx;
    private final @Nullable SnYml config;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    /** One persistent action bar timer per player; replaced on re-call, swept on quit. */
    private final Map<UUID, TaskHandle> persistentBars = new ConcurrentHashMap<>();

    /** Raw template lines per key, fallback already resolved. */
    private final Map<String, List<String>> templates = new ConcurrentHashMap<>();
    /** Pre-rendered components for keys whose lines carry no placeholder token. */
    private final Map<String, List<Component>> rendered = new ConcurrentHashMap<>();

    private volatile YamlConfiguration active = new YamlConfiguration();
    private volatile YamlConfiguration fallback = new YamlConfiguration();
    private volatile String activeCode = FALLBACK_CODE;
    private volatile String prefix = "";

    /**
     * Creates the module, seeding and merging the language files. Instantiated by the
     * context when the spec declares {@code lang()}; consumers reach it through
     * {@code sn.lang()}.
     *
     * @param ctx    owning context
     * @param config mounted main config holding the {@code lang} and {@code update-configs}
     *               keys, or null when the config module was not declared
     */
    public SnLang(Sn ctx, @Nullable SnYml config) {
        this.ctx = ctx;
        this.config = config;
        QuitCleanupListener.register(ctx.plugin(), uuid -> {
            TaskHandle handle = persistentBars.remove(uuid);
            if (handle != null) {
                handle.cancel();
            }
        });
        load();
    }

    /** Active language code, {@code en} when the configured language fell back. */
    public String language() {
        return activeCode;
    }

    /** Sends the message to a player; single-line values get the prefix prepended. */
    public void send(Player target, String key, Ph... phs) {
        send((CommandSender) target, key, phs);
    }

    /** Sends the message to any sender; PAPI resolves per-viewer when it is a player. */
    public void send(CommandSender target, String key, Ph... phs) {
        if (target == null || key == null) {
            return;
        }
        deliver(target, target instanceof Player p ? p : null, key, phs);
    }

    /** Broadcasts the message to the whole server; PAPI resolves against the server. */
    public void broadcast(String key, Ph... phs) {
        if (key == null) {
            return;
        }
        deliver(Bukkit.getServer(), null, key, phs);
    }

    /**
     * Rendered first line of the message, without prefix. Missing keys render as
     * {@code <missing:key>}.
     */
    public Component get(String key, Ph... phs) {
        if (key == null) {
            return Component.empty();
        }
        if (phs == null || phs.length == 0) {
            List<Component> cached = rendered.get(key);
            if (cached != null) {
                return cached.isEmpty() ? Component.empty() : cached.get(0);
            }
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            return missing(key);
        }
        if (lines.isEmpty() || lines.get(0) == null) {
            return Component.empty();
        }
        return renderLine(lines.get(0), null, phs);
    }

    /**
     * First line as a legacy section-code string, for API that still requires legacy
     * text; same resolution and fallback as {@link #get}.
     */
    public String getLegacy(String key, Ph... phs) {
        if (key == null) {
            return "";
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            missing(key);
            return "<missing:" + key + ">";
        }
        if (lines.isEmpty() || lines.get(0) == null) {
            return "";
        }
        return SnText.colorLegacy(resolveLine(lines.get(0), null, phs));
    }

    /** Every line of the message rendered in order; missing keys yield the marker line. */
    public List<Component> getList(String key, Ph... phs) {
        if (key == null) {
            return List.of();
        }
        if (phs == null || phs.length == 0) {
            List<Component> cached = rendered.get(key);
            if (cached != null) {
                return new ArrayList<>(cached);
            }
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            return List.of(missing(key));
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(renderLine(line == null ? "" : line, null, phs));
        }
        return out;
    }

    /** Shows the first line of the message on the player's action bar. */
    public void actionbar(Player target, String key, Ph... phs) {
        if (target == null || key == null) {
            return;
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            target.sendActionBar(missing(key));
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        String line = lines.get(0);
        if (line == null || line.isEmpty()) {
            return;
        }
        target.sendActionBar(renderLine(line, target, phs));
    }

    /**
     * Shows the first line of the message on the player's action bar and keeps it there
     * for {@code hold}: the line is rendered ONCE at call time (PAPI and locals frozen;
     * re-call to refresh dynamic content), sent immediately and re-sent every 40 ticks
     * until the hold expires, then cleared with an empty component. A null, zero or
     * negative hold delegates to {@link #actionbar(Player, String, Ph...)}. A new held
     * bar for the same player replaces and cancels the previous one; a plain action bar
     * sent during a hold is overwritten on the next 40-tick refresh. The timer is
     * cancelled when the player quits and swept by the context shutdown.
     */
    public void actionbar(Player target, String key, Duration hold, Ph... phs) {
        if (target == null || key == null) {
            return;
        }
        if (hold == null || hold.isZero() || hold.isNegative()) {
            actionbar(target, key, phs);
            return;
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            target.sendActionBar(missing(key));
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        String line = lines.get(0);
        if (line == null || line.isEmpty()) {
            return;
        }
        Component content = renderLine(line, target, phs);
        target.sendActionBar(content);
        UUID uuid = target.getUniqueId();
        long deadline = System.nanoTime() + hold.toNanos();
        final TaskHandle[] self = new TaskHandle[1];
        self[0] = ctx.scheduler().timer(1L, ACTIONBAR_REFRESH_TICKS, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                persistentBars.remove(uuid, self[0]);
                self[0].cancel();
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                online.sendActionBar(Component.empty());
                persistentBars.remove(uuid, self[0]);
                self[0].cancel();
                return;
            }
            online.sendActionBar(content);
        });
        TaskHandle previous = persistentBars.put(uuid, self[0]);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * Shows the message as a title. The first line is parsed as
     * {@code title;subtitle;fadeIn;stay;fadeOut} (times in ticks, defaults 10;70;20);
     * omitted parts fall back to their defaults.
     */
    public void title(Player target, String key, Ph... phs) {
        if (target == null || key == null) {
            return;
        }
        List<String> lines = templates.get(key);
        if (lines == null) {
            target.showTitle(Title.title(missing(key), Component.empty()));
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        String raw = lines.get(0);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] parts = raw.split(";", -1);
        Component main = renderLine(parts[0], target, phs);
        Component subtitle = parts.length > 1 ? renderLine(parts[1], target, phs) : Component.empty();
        target.showTitle(Title.title(main, subtitle, Title.Times.times(
                Duration.ofMillis(ticksPart(parts, 2, 10L) * 50L),
                Duration.ofMillis(ticksPart(parts, 3, 70L) * 50L),
                Duration.ofMillis(ticksPart(parts, 4, 20L) * 50L))));
    }

    /**
     * Re-runs the seeding and merges and reloads both language files from disk,
     * rebuilding every cache. Invoked from onEnable and the reload command only.
     */
    public void reload() {
        load();
    }

    // ------------------------------------------------------------------
    // Loading and merging
    // ------------------------------------------------------------------

    private void load() {
        warnedKeys.clear();
        File dir = new File(ctx.plugin().getDataFolder(), LANG_DIR);
        File enFile = new File(dir, "messages_" + FALLBACK_CODE + ".yml");
        seedEnglish(dir, enFile);
        mergeSnlibKeys(enFile);
        this.fallback = parseFile(enFile);
        String code = desiredCode();
        if (FALLBACK_CODE.equals(code)) {
            this.active = this.fallback;
            this.activeCode = FALLBACK_CODE;
        } else {
            loadTranslation(dir, enFile, code);
        }
        cachePrefix();
        buildCaches();
        warnLiteralPrefixToken();
        warnInteractiveTagDrift();
    }

    /**
     * Seeds {@code lang/messages_en.yml} from the consumer jar and always-merges it
     * afterwards; a consumer jar without the resource gets a minimal file plus one WARN.
     */
    private void seedEnglish(File dir, File enFile) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            ctx.plugin().getLogger().warning("Could not create folder " + LANG_DIR + "/");
        }
        if (ctx.plugin().getResource(CONSUMER_RESOURCE) != null) {
            YamlUpdater.update(ctx.plugin(), CONSUMER_RESOURCE, enFile, false);
            return;
        }
        if (enFile.exists()) {
            return;
        }
        try {
            Files.write(enFile.toPath(), List.of(
                    "# Minimal file created by SnLib: the jar of " + ctx.plugin().getName()
                            + " does not include " + CONSUMER_RESOURCE + ".",
                    "# The snlib.* keys are inserted and maintained by SnLib's always-merge updater."),
                    StandardCharsets.UTF_8);
            ctx.plugin().getLogger().warning("The jar of " + ctx.plugin().getName()
                    + " does not include " + CONSUMER_RESOURCE + "; a minimal file was created");
        } catch (IOException ex) {
            ctx.plugin().getLogger().warning("Could not create " + enFile.getName()
                    + ": " + ex.getMessage());
        }
    }

    /**
     * Merges the {@code snlib-messages.yml} bundled inside SnLib.jar into the disk
     * {@code messages_en.yml}. Always-on and exempt from the {@code update-configs}
     * gate: the {@code snlib.*} keys are the library's own message contract.
     */
    private void mergeSnlibKeys(File enFile) {
        List<String> resource = snlibResourceLines();
        if (resource == null) {
            ctx.plugin().getLogger().warning("Resource " + SNLIB_RESOURCE
                    + " absent from SnLib.jar; the snlib.* keys cannot be merged");
            return;
        }
        try {
            List<String> disk = enFile.exists()
                    ? new ArrayList<>(Files.readAllLines(enFile.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            if (!disk.isEmpty() && !YamlUpdater.isParseable(
                    YamlPreprocessor.preprocess(String.join("\n", disk)).cleanText())) {
                ctx.plugin().getLogger().warning(enFile.getName()
                        + " does not parse as YAML; the snlib.* key merge is skipped");
                return;
            }
            List<String> merged = YamlUpdater.merge(resource, disk);
            if (merged.equals(disk)) {
                return;
            }
            File parent = enFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(enFile.toPath(), merged, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ctx.plugin().getLogger().warning("Could not merge the snlib.* keys into "
                    + enFile.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Loads a non-English translation, merging missing keys from the DISK English file
     * as reference (gated by {@code update-configs}); a missing or unreadable file falls
     * back to English with one WARN.
     */
    private void loadTranslation(File dir, File enFile, String code) {
        File langFile = new File(dir, "messages_" + code + ".yml");
        if (!langFile.isFile()) {
            ctx.plugin().getLogger().warning(LANG_DIR + "/" + langFile.getName()
                    + " does not exist; using messages_" + FALLBACK_CODE + ".yml");
            this.active = this.fallback;
            this.activeCode = FALLBACK_CODE;
            return;
        }
        mergeTranslation(enFile, langFile);
        YamlConfiguration parsed = parseFile(langFile);
        if (parsed.getKeys(false).isEmpty()) {
            ctx.plugin().getLogger().warning(LANG_DIR + "/" + langFile.getName()
                    + " is empty or corrupt; using messages_" + FALLBACK_CODE + ".yml");
            this.active = this.fallback;
            this.activeCode = FALLBACK_CODE;
            return;
        }
        this.active = parsed;
        this.activeCode = code;
    }

    private void mergeTranslation(File enFile, File langFile) {
        try {
            List<String> reference = Files.readAllLines(enFile.toPath(), StandardCharsets.UTF_8);
            boolean changed = YamlUpdater.updateFromLines(ctx.plugin(), reference, langFile,
                    config != null ? config.file() : null);
            if (changed) {
                ctx.plugin().getLogger().info("[update-configs] New keys from messages_"
                        + FALLBACK_CODE + ".yml added to " + LANG_DIR + "/"
                        + langFile.getName() + "; translate them when convenient");
            }
        } catch (IOException ex) {
            ctx.plugin().getLogger().warning("Could not merge translation "
                    + langFile.getName() + ": " + ex.getMessage());
        }
    }

    private String desiredCode() {
        if (config == null) {
            return FALLBACK_CODE;
        }
        String code = config.getString("lang", FALLBACK_CODE);
        if (code == null || code.isBlank()) {
            return FALLBACK_CODE;
        }
        return code.trim().toLowerCase(Locale.ROOT);
    }

    /** Tab-tolerant parse; unreadable content yields an empty configuration plus WARN. */
    private YamlConfiguration parseFile(File file) {
        YamlConfiguration cfg = new YamlConfiguration();
        if (!file.exists()) {
            return cfg;
        }
        try {
            String raw = YamlPreprocessor.read(file.toPath());
            YamlPreprocessor.Result result = YamlPreprocessor.preprocess(raw);
            if (!result.fixedLines().isEmpty()) {
                ctx.plugin().getLogger().warning("Indentation tabs fixed in "
                        + file.getName() + " (lines " + result.fixedLines() + ")");
            }
            cfg.loadFromString(result.cleanText());
        } catch (IOException | InvalidConfigurationException ex) {
            ctx.plugin().getLogger().warning("Could not read " + LANG_DIR + "/"
                    + file.getName() + ": " + ex.getMessage());
        }
        return cfg;
    }

    // ------------------------------------------------------------------
    // Caches and resolution
    // ------------------------------------------------------------------

    private void cachePrefix() {
        String raw = null;
        if (active.isString("prefix")) {
            raw = active.getString("prefix");
        } else if (active != fallback && fallback.isString("prefix")) {
            raw = fallback.getString("prefix");
        }
        this.prefix = raw == null ? "" : raw;
    }

    private void buildCaches() {
        templates.clear();
        rendered.clear();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(leafKeys(fallback));
        keys.addAll(leafKeys(active));
        for (String key : keys) {
            List<String> lines = linesFor(key);
            if (lines == null) {
                continue;
            }
            templates.put(key, List.copyOf(lines));
            if (isStatic(lines)) {
                List<Component> out = new ArrayList<>(lines.size());
                for (String line : lines) {
                    out.add(renderLine(line == null ? "" : line, null));
                }
                rendered.put(key, List.copyOf(out));
            }
        }
    }

    private static Set<String> leafKeys(YamlConfiguration cfg) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Active language first; a key only in English warns once and falls back. */
    private @Nullable List<String> linesFor(String key) {
        List<String> fromActive = readLines(active, key);
        if (fromActive != null) {
            return fromActive;
        }
        if (active != fallback) {
            List<String> fromFallback = readLines(fallback, key);
            if (fromFallback != null && warnedKeys.add("fallback:" + key)) {
                ctx.plugin().getLogger().warning("Key '" + key + "' missing in messages_"
                        + activeCode + ".yml; using the value from messages_" + FALLBACK_CODE + ".yml");
            }
            return fromFallback;
        }
        return null;
    }

    private static @Nullable List<String> readLines(YamlConfiguration cfg, String key) {
        if (cfg.isList(key)) {
            return cfg.getStringList(key);
        }
        if (cfg.isString(key)) {
            return List.of(cfg.getString(key, ""));
        }
        return null;
    }

    /**
     * Defense-in-depth WARN: SnLib prepends the configured {@code prefix} to single-line
     * messages automatically, so a value that also embeds the {@link #LITERAL_PREFIX_TOKEN}
     * token would render it verbatim. Scans the active language file once per load and, when
     * any value carries the token, logs a single summary WARN naming how many keys are
     * affected. One warning per load, never per key.
     */
    private void warnLiteralPrefixToken() {
        Map<String, List<String>> valuesByKey = new LinkedHashMap<>();
        for (String key : leafKeys(active)) {
            List<String> values = readLines(active, key);
            if (values != null) {
                valuesByKey.put(key, values);
            }
        }
        int affected = countKeysWithLiteralPrefixToken(valuesByKey);
        if (affected > 0) {
            ctx.plugin().getLogger().warning(affected + " message key(s) in " + LANG_DIR
                    + "/messages_" + activeCode + ".yml embed the literal " + LITERAL_PREFIX_TOKEN
                    + " token; SnLib prepends the configured prefix automatically, so the token"
                    + " renders literally - remove it from those values");
        }
    }

    /** Counts how many keys carry the literal prefix token; a multi-line value counts once. */
    static int countKeysWithLiteralPrefixToken(Map<String, List<String>> valuesByKey) {
        int affected = 0;
        for (List<String> values : valuesByKey.values()) {
            if (carriesLiteralPrefixToken(values)) {
                affected++;
            }
        }
        return affected;
    }

    /** True when any line of the value contains the literal prefix token. */
    static boolean carriesLiteralPrefixToken(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.contains(LITERAL_PREFIX_TOKEN)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Defense-in-depth WARN: the always-merge updater never rewrites an existing lang
     * value, so a {@code <click:...>}/{@code <hover:...>} tag present in the jar reference
     * but lost from the live value (admin edit, translation) keeps the button LOOK while
     * the click silently dies. Compares the consumer jar resource against the resolved
     * templates once per load and logs a single summary WARN naming the affected keys.
     * One warning per load, never per key.
     */
    private void warnInteractiveTagDrift() {
        YamlConfiguration reference = parseResource(CONSUMER_RESOURCE);
        if (reference == null) {
            return;
        }
        List<String> affected = new ArrayList<>();
        for (String key : leafKeys(reference)) {
            if (lostInteractiveMarker(readLines(reference, key), templates.get(key))) {
                affected.add(key);
            }
        }
        if (!affected.isEmpty()) {
            ctx.plugin().getLogger().warning(affected.size() + " message key(s) in " + LANG_DIR
                    + "/messages_" + activeCode + ".yml lost the <click>/<hover> tag their jar"
                    + " default carries (" + String.join(", ", affected) + "); the button still"
                    + " renders but clicking it does nothing - restore the tags in those values");
        }
    }

    /** True when the reference value carries an interactive marker the live value lost. */
    static boolean lostInteractiveMarker(@Nullable List<String> reference,
                                         @Nullable List<String> live) {
        for (String marker : INTERACTIVE_MARKERS) {
            if (containsMarker(reference, marker) && !containsMarker(live, marker)) {
                return true;
            }
        }
        return false;
    }

    /** True when any line of the value contains the marker, case-insensitively. */
    static boolean containsMarker(@Nullable List<String> values, String marker) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Parses a jar resource as YAML (tab-tolerant); null when absent or unreadable. */
    private @Nullable YamlConfiguration parseResource(String path) {
        try (InputStream in = ctx.plugin().getResource(path)) {
            if (in == null) {
                return null;
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(YamlPreprocessor.preprocess(raw).cleanText());
            return cfg;
        } catch (IOException | InvalidConfigurationException ex) {
            return null;
        }
    }

    /** Static means renderable once: no {@code %token%} and no <code>{token}</code>. */
    private static boolean isStatic(List<String> lines) {
        for (String line : lines) {
            if (line != null && (line.indexOf('%') >= 0 || line.indexOf('{') >= 0)) {
                return false;
            }
        }
        return true;
    }

    private void deliver(Audience audience, @Nullable Player viewer, String key, Ph... phs) {
        List<String> lines = templates.get(key);
        if (lines == null) {
            audience.sendMessage(missing(key));
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        if (lines.size() == 1) {
            String line = lines.get(0);
            if (line == null || line.isEmpty()) {
                return;
            }
            if ((phs == null || phs.length == 0) && (prefix.isEmpty() || skipsPrefix(line))) {
                List<Component> cached = rendered.get(key);
                if (cached != null && !cached.isEmpty()) {
                    audience.sendMessage(cached.get(0));
                    return;
                }
            }
            audience.sendMessage(renderLine(withPrefix(line), viewer, phs));
            return;
        }
        for (String line : lines) {
            audience.sendMessage(renderLine(line == null ? "" : line, viewer, phs));
        }
    }

    /**
     * Inserts the configured prefix AFTER any leading {@code [center]}/{@code [rgb]}/
     * {@code [small]} tags: a prefixed message keeps its tags at position 0 so they still
     * render. The prefix inserted after {@code [small]} stays INSIDE the tag's scope and
     * renders in small caps, consistent with {@code [rgb]} applying its gradient to the
     * prefix. A {@code [noprefix]} tag anywhere in the leading tag run opts the line out:
     * nothing is inserted and the tag itself is stripped by the SnText render.
     */
    private String withPrefix(String line) {
        if (prefix.isEmpty() || skipsPrefix(line)) {
            return line;
        }
        int i = 0;
        while (true) {
            if (line.regionMatches(true, i, "[center]", 0, 8)) {
                i += 8;
            } else if (line.regionMatches(true, i, "[rgb]", 0, 5)) {
                i += 5;
            } else if (line.regionMatches(true, i, "[small]", 0, 7)) {
                i += 7;
            } else {
                break;
            }
        }
        return i == 0 ? prefix + line : line.substring(0, i) + prefix + line.substring(i);
    }

    /**
     * True when the line's leading tag run carries {@code [noprefix]}, case-insensitive
     * and in any order among {@code [center]}/{@code [rgb]}/{@code [small]}. A tag after
     * the first visible character does not opt out, consistent with every other prefix
     * tag being leading-only.
     */
    static boolean skipsPrefix(String line) {
        int i = 0;
        while (true) {
            if (line.regionMatches(true, i, NOPREFIX_TAG, 0, NOPREFIX_TAG.length())) {
                return true;
            }
            if (line.regionMatches(true, i, "[center]", 0, 8)) {
                i += 8;
            } else if (line.regionMatches(true, i, "[rgb]", 0, 5)) {
                i += 5;
            } else if (line.regionMatches(true, i, "[small]", 0, 7)) {
                i += 7;
            } else {
                return false;
            }
        }
    }

    /** Fixed pipeline: locals, PAPI per viewer, PAPI output normalization, SnText render. */
    private Component renderLine(String line, @Nullable Player viewer, Ph... phs) {
        return SnText.color(resolveLine(line, viewer, phs));
    }

    private String resolveLine(String line, @Nullable Player viewer, Ph... phs) {
        String s = SnText.applyLocals(line, phs);
        s = ctx.papi().apply(viewer, s);
        return SnText.normalizePapiOutput(s);
    }

    private Component missing(String key) {
        if (warnedKeys.add("missing:" + key)) {
            ctx.plugin().getLogger().warning("Message key '" + key + "' does not exist in "
                    + LANG_DIR + "/messages_" + FALLBACK_CODE + ".yml");
        }
        return Component.text("<missing:" + key + ">");
    }

    private static long ticksPart(String[] parts, int index, long def) {
        if (parts.length <= index || parts[index].isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(parts[index].trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private @Nullable List<String> snlibResourceLines() {
        try (InputStream in = SnLang.class.getResourceAsStream("/" + SNLIB_RESOURCE)) {
            if (in == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException ex) {
            return null;
        }
    }
}
