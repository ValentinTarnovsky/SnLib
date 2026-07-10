package com.sn.lib.yml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import com.sn.lib.Sn;

/**
 * Yml module of a consumer context, reached through {@code sn.yml()}. Owns every
 * {@link SnYml} of the plugin, keyed by path relative to the data folder, and mounts
 * the managed main config at construction.
 *
 * <p>File modes, decided by the FIRST mount of each path:</p>
 * <ul>
 *   <li>{@link #managed}: seeded from the jar when absent and always-merged through
 *       {@link YamlUpdater} on every mount and reload, gated by {@code update-configs}.</li>
 *   <li>{@link #managedPruning}: managed plus opt-in removal of keys absent from the
 *       jar resource; the only mode that ever deletes.</li>
 *   <li>{@link #seedOnly}: seeded from the jar when absent, never merged.</li>
 *   <li>{@link #data} / {@link #load}: never seeded, never merged; {@code data} is for
 *       files the plugin writes at runtime, {@code load} for arbitrary reads.</li>
 * </ul>
 *
 * <p>The main config is EXEMPT from the {@code update-configs} gate so the key itself
 * can arrive through a merge; when the seeded config lacks the key it is appended with
 * a comment. Synchronous I/O by design: mounting and {@link #reloadAll()} run only in
 * onEnable and in the reload command, never during gameplay.</p>
 */
public final class YmlManager {

    private static final String GATE_KEY = "update-configs";
    private static final String GATE_COMMENT =
            "# Master gate of the always-merge updater: false skips every yml merge except this file.";

    private enum Mode { MANAGED, SEED_ONLY, PLAIN }

    private record Entry(SnYml yml, String resourcePath, Mode mode, boolean prune, boolean isConfig) {
    }

    private final Sn ctx;
    private final String configPath;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final SnYml config;

    /**
     * Creates the manager and mounts the managed main config. Instantiated by the
     * context; consumers reach it through {@code sn.yml()}.
     *
     * @param ctx        owning context
     * @param configName main config file declared in the spec, for example {@code config.yml}
     */
    public YmlManager(Sn ctx, String configName) {
        this.ctx = ctx;
        this.configPath = normalize(configName);
        this.config = mountConfig();
    }

    /** Managed main config; the {@code update-configs} master key is seeded when absent. */
    public SnYml config() {
        return config;
    }

    /** Managed file: seeded when absent, always-merged from the jar resource, never pruned. */
    public SnYml managed(String path) {
        return mount(path, Mode.MANAGED, false);
    }

    /** Managed file with opt-in pruning: keys removed from the jar resource are deleted from disk. */
    public SnYml managedPruning(String path) {
        return mount(path, Mode.MANAGED, true);
    }

    /** Seeded from the jar when absent; existing content is never merged nor touched. */
    public SnYml seedOnly(String path) {
        return mount(path, Mode.SEED_ONLY, false);
    }

    /** Runtime data file fully owned by the plugin: never seeded, never merged. */
    public SnYml data(String path) {
        return mount(path, Mode.PLAIN, false);
    }

    /** Arbitrary yml under the data folder, read as-is: never seeded, never merged. */
    public SnYml load(String path) {
        return mount(path, Mode.PLAIN, false);
    }

    /**
     * Re-runs the merge of every managed file (config first, so the gate is fresh) and
     * reloads every mounted file from disk, firing their reload hooks.
     */
    public void reloadAll() {
        for (Entry entry : snapshot()) {
            if (entry.mode() == Mode.MANAGED) {
                YamlUpdater.update(ctx.plugin(), entry.resourcePath(), entry.yml().file(),
                        entry.prune(), gateFile(), entry.isConfig());
            } else if (entry.mode() == Mode.SEED_ONLY) {
                YamlUpdater.seedIfMissing(ctx.plugin(), entry.resourcePath(), entry.yml().file());
            }
            entry.yml().reload();
        }
    }

    /** Drains the pending coalesced write of every mounted file; used by the teardown. */
    public void flushAll() {
        for (Entry entry : snapshot()) {
            entry.yml().flush();
        }
    }

    private List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries.values());
        }
    }

    private SnYml mountConfig() {
        synchronized (entries) {
            File disk = fileFor(configPath);
            YamlUpdater.update(ctx.plugin(), configPath, disk, false, null, true);
            ensureGateKey(disk);
            SnYml yml = new SnYml(ctx, disk);
            entries.put(configPath, new Entry(yml, configPath, Mode.MANAGED, false, true));
            return yml;
        }
    }

    private SnYml mount(String rawPath, Mode mode, boolean prune) {
        String path = normalize(rawPath);
        synchronized (entries) {
            Entry existing = entries.get(path);
            if (existing != null) {
                return existing.yml();
            }
            File disk = fileFor(path);
            if (mode == Mode.MANAGED) {
                YamlUpdater.update(ctx.plugin(), path, disk, prune, gateFile(), false);
            } else if (mode == Mode.SEED_ONLY) {
                YamlUpdater.seedIfMissing(ctx.plugin(), path, disk);
            }
            SnYml yml = new SnYml(ctx, disk);
            entries.put(path, new Entry(yml, path, mode, prune, false));
            return yml;
        }
    }

    /**
     * Guarantees the {@code update-configs} key exists in the config on disk, appending
     * it with its comment when the seeded resource lacks it.
     */
    private void ensureGateKey(File disk) {
        try {
            if (!disk.exists()) {
                Path parent = disk.toPath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(disk.toPath(), List.of(GATE_COMMENT, GATE_KEY + ": true"),
                        StandardCharsets.UTF_8);
                return;
            }
            String raw = YamlPreprocessor.read(disk.toPath());
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(YamlPreprocessor.preprocess(raw).cleanText());
            if (cfg.isSet(GATE_KEY)) {
                return;
            }
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(disk.toPath(), StandardCharsets.UTF_8));
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.add("");
            }
            lines.add(GATE_COMMENT);
            lines.add(GATE_KEY + ": true");
            Files.write(disk.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException | InvalidConfigurationException ex) {
            ctx.plugin().getLogger().warning("No se pudo seedear la key " + GATE_KEY + " en "
                    + disk.getName() + ": " + ex.getMessage());
        }
    }

    private File gateFile() {
        return fileFor(configPath);
    }

    private File fileFor(String path) {
        return new File(ctx.plugin().getDataFolder(), path);
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
