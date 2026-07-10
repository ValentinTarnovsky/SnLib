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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * {@code prefix} value prepended, list values are sent line by line as-is.</p>
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

    private final Sn ctx;
    private final @Nullable SnYml config;
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

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
    }

    /**
     * Seeds {@code lang/messages_en.yml} from the consumer jar and always-merges it
     * afterwards; a consumer jar without the resource gets a minimal file plus one WARN.
     */
    private void seedEnglish(File dir, File enFile) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            ctx.plugin().getLogger().warning("No se pudo crear la carpeta " + LANG_DIR + "/");
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
                    "# Archivo minimo creado por SnLib: el jar de " + ctx.plugin().getName()
                            + " no incluye " + CONSUMER_RESOURCE + ".",
                    "# Las keys snlib.* las inserta y mantiene el updater always-merge de SnLib."),
                    StandardCharsets.UTF_8);
            ctx.plugin().getLogger().warning("El jar de " + ctx.plugin().getName()
                    + " no incluye " + CONSUMER_RESOURCE + "; se creo un archivo minimo");
        } catch (IOException ex) {
            ctx.plugin().getLogger().warning("No se pudo crear " + enFile.getName()
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
            ctx.plugin().getLogger().warning("Recurso " + SNLIB_RESOURCE
                    + " ausente de SnLib.jar; las keys snlib.* no se pueden mergear");
            return;
        }
        try {
            List<String> disk = enFile.exists()
                    ? new ArrayList<>(Files.readAllLines(enFile.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            if (!disk.isEmpty() && !YamlUpdater.isParseable(
                    YamlPreprocessor.preprocess(String.join("\n", disk)).cleanText())) {
                ctx.plugin().getLogger().warning(enFile.getName()
                        + " no parsea como YAML; se omite el merge de keys snlib.*");
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
            ctx.plugin().getLogger().warning("No se pudieron mergear las keys snlib.* en "
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
                    + " no existe; usando messages_" + FALLBACK_CODE + ".yml");
            this.active = this.fallback;
            this.activeCode = FALLBACK_CODE;
            return;
        }
        mergeTranslation(enFile, langFile);
        YamlConfiguration parsed = parseFile(langFile);
        if (parsed.getKeys(false).isEmpty()) {
            ctx.plugin().getLogger().warning(LANG_DIR + "/" + langFile.getName()
                    + " esta vacio o corrupto; usando messages_" + FALLBACK_CODE + ".yml");
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
            List<String> disk = Files.readAllLines(langFile.toPath(), StandardCharsets.UTF_8);
            if (!YamlUpdater.isParseable(
                    YamlPreprocessor.preprocess(String.join("\n", disk)).cleanText())) {
                ctx.plugin().getLogger().warning(langFile.getName()
                        + " no parsea como YAML; se omite el merge de traduccion");
                return;
            }
            List<String> merged = YamlUpdater.merge(reference, disk);
            if (merged.equals(disk)) {
                return;
            }
            if (config != null && !config.getBoolean("update-configs", true)) {
                ctx.plugin().getLogger().warning("[update-configs] update-configs esta en false: "
                        + "faltan keys en " + LANG_DIR + "/" + langFile.getName());
                return;
            }
            Files.write(langFile.toPath(), merged, StandardCharsets.UTF_8);
            ctx.plugin().getLogger().info("[update-configs] Keys nuevas de messages_"
                    + FALLBACK_CODE + ".yml agregadas a " + LANG_DIR + "/" + langFile.getName()
                    + "; traducirlas cuando convenga");
        } catch (IOException ex) {
            ctx.plugin().getLogger().warning("No se pudo mergear la traduccion "
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
                ctx.plugin().getLogger().warning("Tabs de indentacion corregidos en "
                        + file.getName() + " (lineas " + result.fixedLines() + ")");
            }
            cfg.loadFromString(result.cleanText());
        } catch (IOException | InvalidConfigurationException ex) {
            ctx.plugin().getLogger().warning("No se pudo leer " + LANG_DIR + "/"
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
                ctx.plugin().getLogger().warning("Key '" + key + "' falta en messages_"
                        + activeCode + ".yml; usando el valor de messages_" + FALLBACK_CODE + ".yml");
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
            if (prefix.isEmpty() && (phs == null || phs.length == 0)) {
                List<Component> cached = rendered.get(key);
                if (cached != null && !cached.isEmpty()) {
                    audience.sendMessage(cached.get(0));
                    return;
                }
            }
            audience.sendMessage(renderLine(prefix + line, viewer, phs));
            return;
        }
        for (String line : lines) {
            audience.sendMessage(renderLine(line == null ? "" : line, viewer, phs));
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
            ctx.plugin().getLogger().warning("Key de mensaje '" + key + "' no existe en "
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
