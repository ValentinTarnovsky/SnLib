package com.sn.lib.yml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.sn.lib.Sn;
import com.sn.lib.debug.SnDebug;
import com.sn.lib.text.SnText;

/**
 * One YAML file owned by a consumer context: tab-tolerant loading, typed
 * placeholder-aware getters with fallback plus WARN, and coalesced async saving.
 *
 * <p>Instances are created by the context's yml manager, one per file. The text is
 * repaired by {@link YamlPreprocessor} before parsing, so tab-indented files load with a
 * single warning instead of failing.</p>
 *
 * <p>Getter resolution: local placeholders registered via {@link #placeholder} are applied
 * first; PAPI tokens are applied only on the primary thread (off the main thread they are
 * left intact so async callers never trigger a PAPI lookup). Getters without a viewer
 * resolve PAPI against the server (null player). A value of the wrong type falls back to
 * the given default and logs one WARN; an absent key returns the default silently, and
 * {@code isSet} keeps an explicit 0/false distinguishable from an absent key.</p>
 *
 * <p>Saving: {@link #save()} snapshots the serialized text on the calling thread and
 * writes it off-thread with coalescing (at most one pending write per file; a newer save
 * replaces the pending snapshot). Once the owning context is shutting down, saves write
 * synchronously on the calling thread, never through the scheduler. {@link #flush()}
 * drains whatever is still pending and is invoked by the context teardown.</p>
 */
public final class SnYml {

    private final Sn ctx;
    private final File file;
    private final Map<String, Supplier<String>> locales = new ConcurrentHashMap<>();
    private final List<Runnable> reloadHooks = new CopyOnWriteArrayList<>();

    private volatile YamlConfiguration yaml = new YamlConfiguration();

    private final Object saveLock = new Object();
    private String pendingSnapshot;
    private long pendingSeq;
    private CompletableFuture<?> pendingWrite;
    private boolean writeScheduled;

    private final Object ioLock = new Object();
    private long saveSeq;
    private long lastAttemptedSeq;

    SnYml(Sn ctx, File file) {
        this.ctx = ctx;
        this.file = file;
        loadFromDisk();
    }

    /** Backing file on disk. */
    public File file() {
        return file;
    }

    /** Resolved string value; absent key returns {@code def} silently. */
    public String getString(String key, String def) {
        return getString(key, def, null);
    }

    /** Resolved string value; PAPI tokens resolve per-viewer when one is given. */
    public String getString(String key, String def, Player viewer) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (raw instanceof String s) {
            return resolve(s, viewer);
        }
        warnInvalid(key, raw, def);
        return resolve(String.valueOf(raw), viewer);
    }

    /** Integer value; numbers are read directly, strings are resolved then parsed. */
    public int getInt(String key, int def) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(resolve(s, null).trim());
            } catch (NumberFormatException e) {
                warnInvalid(key, s, def);
                return def;
            }
        }
        warnInvalid(key, raw, def);
        return def;
    }

    /** Double value; numbers are read directly, strings are resolved then parsed. */
    public double getDouble(String key, double def) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(resolve(s, null).trim());
            } catch (NumberFormatException e) {
                warnInvalid(key, s, def);
                return def;
            }
        }
        warnInvalid(key, raw, def);
        return def;
    }

    /** Long value; numbers are read directly, strings are resolved then parsed. */
    public long getLong(String key, long def) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(resolve(s, null).trim());
            } catch (NumberFormatException e) {
                warnInvalid(key, s, def);
                return def;
            }
        }
        warnInvalid(key, raw, def);
        return def;
    }

    /** Boolean value; only literal {@code true}/{@code false} strings parse. */
    public boolean getBoolean(String key, boolean def) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String resolved = resolve(s, null).trim();
            if (resolved.equalsIgnoreCase("true")) {
                return true;
            }
            if (resolved.equalsIgnoreCase("false")) {
                return false;
            }
            warnInvalid(key, s, def);
            return def;
        }
        warnInvalid(key, raw, def);
        return def;
    }

    /** String list with every element resolved; absent key returns {@code def} silently. */
    public List<String> getStringList(String key, List<String> def) {
        return getStringList(key, def, null);
    }

    /** String list resolved per-viewer when one is given. */
    public List<String> getStringList(String key, List<String> def, Player viewer) {
        Object raw = yaml.get(key);
        if (raw == null) {
            if (!yaml.isSet(key)) {
                return def;
            }
            warnInvalid(key, null, def);
            return def;
        }
        if (!(raw instanceof List<?> rawList)) {
            warnInvalid(key, raw, def);
            return def;
        }
        List<String> out = new ArrayList<>(rawList.size());
        for (Object elem : rawList) {
            out.add(resolve(elem == null ? "" : String.valueOf(elem), viewer));
        }
        return out;
    }

    /** Raw configuration section, or null when absent; values read from it bypass resolve. */
    public ConfigurationSection getSection(String key) {
        return yaml.getConfigurationSection(key);
    }

    /** True when the key exists in the file, even with a 0/false/empty value. */
    public boolean isSet(String key) {
        return yaml.isSet(key);
    }

    /** Sets an in-memory value; call {@link #save()} to persist it. */
    public void set(String key, Object value) {
        yaml.set(key, value);
    }

    /**
     * Persists the current state. In normal runtime the serialized snapshot is taken on
     * the calling thread and written off-thread with coalescing: at most one write is
     * pending per file and a newer save replaces the pending snapshot. Once the owning
     * context is shutting down the write happens synchronously on the calling thread,
     * never through the scheduler.
     */
    public void save() {
        String snapshot = yaml.saveToString();
        if (ctx.isShuttingDown()) {
            long seq;
            synchronized (saveLock) {
                pendingSnapshot = null;
                seq = ++saveSeq;
            }
            writeToDisk(snapshot, seq);
            return;
        }
        synchronized (saveLock) {
            pendingSnapshot = snapshot;
            pendingSeq = ++saveSeq;
            if (!writeScheduled) {
                writeScheduled = true;
                CompletableFuture<?> write = ctx.scheduler().supplyAsync(this::drainPendingWrites);
                pendingWrite = write;
                write.whenComplete((value, error) -> {
                    if (error != null) {
                        synchronized (saveLock) {
                            writeScheduled = false;
                        }
                    }
                });
            }
        }
    }

    /**
     * Drains any pending save: joins the in-flight async write, then writes the leftover
     * snapshot synchronously if the async write never started (scheduler rejected or
     * already cancelled). Used by the context teardown so no coalesced write is lost.
     */
    public void flush() {
        CompletableFuture<?> write;
        synchronized (saveLock) {
            write = pendingWrite;
        }
        if (write != null) {
            try {
                write.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Exceptional or timed-out write: the leftover snapshot is handled below.
            }
        }
        String snapshot;
        long seq;
        synchronized (saveLock) {
            snapshot = pendingSnapshot;
            seq = pendingSeq;
            pendingSnapshot = null;
        }
        if (snapshot != null) {
            writeToDisk(snapshot, seq);
        }
    }

    /** Registers a local placeholder resolved before any PAPI lookup. */
    public SnYml placeholder(String key, Supplier<String> value) {
        locales.put(key, value);
        return this;
    }

    /** Registers a batch of local placeholders; see {@link #placeholder}. */
    public SnYml placeholders(Map<String, Supplier<String>> values) {
        locales.putAll(values);
        return this;
    }

    /** Registers a hook fired after every {@link #reload()}. */
    public void onReload(Runnable hook) {
        reloadHooks.add(hook);
    }

    /** Re-reads the file from disk (preprocessing tabs) and fires the reload hooks. */
    public void reload() {
        loadFromDisk();
        for (Runnable hook : reloadHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning(
                        "Hook de reload fallo para " + file.getName() + ": " + t);
            }
        }
    }

    private void loadFromDisk() {
        if (!file.exists()) {
            this.yaml = new YamlConfiguration();
            return;
        }
        try {
            String raw = YamlPreprocessor.read(file.toPath());
            YamlPreprocessor.Result result = YamlPreprocessor.preprocess(raw);
            if (!result.fixedLines().isEmpty()) {
                ctx.plugin().getLogger().warning("Tabs de indentacion corregidos en "
                        + file.getName() + " (lineas " + result.fixedLines() + ")");
            }
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.loadFromString(result.cleanText());
            this.yaml = loaded;
        } catch (IOException | InvalidConfigurationException e) {
            ctx.plugin().getLogger().warning("No se pudo leer " + file.getName() + ": "
                    + e.getMessage() + "; se mantiene el contenido anterior");
        }
    }

    /**
     * Locals first, then PAPI only on the primary thread. Off the main thread PAPI tokens
     * are left intact and the skip is recorded through the context debug service.
     */
    private String resolve(String s, Player viewer) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = SnText.applyLocals(s, this::localValue);
        if (out.indexOf('%') >= 0) {
            if (Bukkit.isPrimaryThread()) {
                out = applyPapi(viewer, out);
            } else {
                SnDebug debug = ctx.debug();
                if (debug != null) {
                    String skipped = out;
                    debug.log(() -> "PAPI omitido fuera del main thread en " + file.getName()
                            + "; tokens intactos: " + skipped);
                }
            }
        }
        return out;
    }

    private String localValue(String key) {
        Supplier<String> supplier = locales.get(key);
        return supplier == null ? null : supplier.get();
    }

    /**
     * PAPI application point, reached only on the primary thread. Identity until the papi
     * module wires the per-context expansion service here.
     */
    private String applyPapi(Player viewer, String s) {
        return s;
    }

    private Void drainPendingWrites() {
        while (true) {
            String snapshot;
            long seq;
            synchronized (saveLock) {
                snapshot = pendingSnapshot;
                seq = pendingSeq;
                pendingSnapshot = null;
                if (snapshot == null) {
                    writeScheduled = false;
                    return null;
                }
            }
            writeToDisk(snapshot, seq);
        }
    }

    private void writeToDisk(String content, long seq) {
        synchronized (ioLock) {
            // Un snapshot mas viejo que uno ya intentado JAMAS pisa el estado nuevo
            // (carrera drain async vs save sincrono de teardown).
            if (seq <= lastAttemptedSeq) {
                return;
            }
            lastAttemptedSeq = seq;
            try {
                Path parent = file.toPath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                ctx.plugin().getLogger().warning(
                        "No se pudo guardar " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private void warnInvalid(String key, Object value, Object def) {
        ctx.plugin().getLogger().warning("Valor invalido en " + file.getName() + " -> '" + key
                + "': se recibio '" + value + "', usando default '" + def + "'");
    }
}
