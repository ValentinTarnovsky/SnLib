package com.sn.lib.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.yml.SnYml;

/**
 * Runtime debug service of a consumer context, reached through {@code sn.debug()}:
 * toggleable without restart, with string categories, lazy message suppliers and
 * persistence of every toggle.
 *
 * <p>Output goes to the server logger, prefixed per channel: {@link #info} emits with
 * {@code [<PluginName>][INFO]}, {@link #log} with {@code [<PluginName>][DEBUG]} and
 * {@link #trace} with {@code [<PluginName>][TRACE]}. A channel flows only while the
 * master toggle is on and the level reaches its severity ({@code INFO} for info,
 * {@code DEBUG} for log, {@code TRACE} for trace). The category filter is empty by
 * default, which lets every category through; toggling a category in narrows the
 * output to the filtered ones.</p>
 *
 * <p>Persistence: state is read from {@code debug.enabled}, {@code debug.level} and
 * {@code debug.categories} of the backing yml, and every toggle writes back through
 * {@link SnYml#set} plus {@link SnYml#save()} (coalesced async in runtime; synchronous
 * once the owning context is shutting down). Without a backing yml (no config module
 * declared) toggles work in memory only.</p>
 */
public final class SnDebug {

    /**
     * Verbosity threshold, an escalating ladder: {@code OFF < INFO < DEBUG < TRACE}.
     * Each channel emits from its own step up: {@link #info} from {@code INFO},
     * {@link #log} from {@code DEBUG} and {@link #trace} only at {@code TRACE}.
     * {@code OFF} silences every channel.
     */
    public enum Level { OFF, INFO, DEBUG, TRACE }

    private static final String KEY_ENABLED = "debug.enabled";
    private static final String KEY_LEVEL = "debug.level";
    private static final String KEY_CATEGORIES = "debug.categories";

    private final JavaPlugin plugin;
    private final @Nullable SnYml storage;
    private final String prefixInfo;
    private final String prefixDebug;
    private final String prefixTrace;
    private final Set<String> categories = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile Level level = Level.DEBUG;

    /**
     * Creates the service, restoring persisted state when a backing yml is given.
     *
     * @param plugin  consumer plugin that owns this service
     * @param storage yml holding the {@code debug.*} keys (the mounted main config),
     *                or null to keep every toggle in memory only
     */
    public SnDebug(JavaPlugin plugin, @Nullable SnYml storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.prefixInfo = "[" + plugin.getName() + "][INFO] ";
        this.prefixDebug = "[" + plugin.getName() + "][DEBUG] ";
        this.prefixTrace = "[" + plugin.getName() + "][TRACE] ";
        if (storage != null) {
            this.enabled = storage.getBoolean(KEY_ENABLED, false);
            this.level = parseLevel(storage.getString(KEY_LEVEL, Level.DEBUG.name()));
            for (String category : storage.getStringList(KEY_CATEGORIES, List.of())) {
                categories.add(normalize(category));
            }
        }
    }

    /** Logs the message on the INFO channel when the level is at least {@code INFO}. */
    public void info(String message) {
        if (infoEnabled()) {
            print(prefixInfo, message);
        }
    }

    /** Builds and logs the message lazily on the INFO channel. */
    public void info(Supplier<String> message) {
        if (infoEnabled()) {
            print(prefixInfo, message.get());
        }
    }

    /** Logs the message when debug output is enabled. */
    public void log(String message) {
        if (enabled()) {
            print(prefixDebug, message);
        }
    }

    /** Builds and logs the message lazily, only when debug output is enabled. */
    public void log(Supplier<String> message) {
        if (enabled()) {
            print(prefixDebug, message.get());
        }
    }

    /** Builds and logs the message lazily under a category, honoring the category filter. */
    public void log(String category, Supplier<String> message) {
        if (enabled(category)) {
            print(prefixDebug, "[" + normalize(category) + "] " + message.get());
        }
    }

    /** Builds and logs the message lazily on the TRACE channel, only at {@code TRACE}. */
    public void trace(Supplier<String> message) {
        if (tracing()) {
            print(prefixTrace, message.get());
        }
    }

    /**
     * Builds and logs the message lazily on the TRACE channel under a category, honoring
     * the same category filter as {@link #log(String, Supplier)}: an empty filter lets
     * every category through.
     */
    public void trace(String category, Supplier<String> message) {
        if (tracing() && (categories.isEmpty() || categories.contains(normalize(category)))) {
            print(prefixTrace, "[" + normalize(category) + "] " + message.get());
        }
    }

    /** True while output is emitted: master toggle on and level at least {@code DEBUG}. */
    public boolean enabled() {
        return enabled && level.ordinal() >= Level.DEBUG.ordinal();
    }

    /** True while the TRACE channel emits: master toggle on and level at {@code TRACE}. */
    public boolean tracing() {
        return enabled && level.ordinal() >= Level.TRACE.ordinal();
    }

    /** True when the category passes: {@link #enabled()} and filter empty or containing it. */
    public boolean enabled(String category) {
        return enabled() && (categories.isEmpty() || categories.contains(normalize(category)));
    }

    /** Flips the master toggle, persists it and returns the new state. */
    public boolean toggle() {
        enabled = !enabled;
        persist();
        return enabled;
    }

    /**
     * Adds the category to the filter, or removes it if already present, and persists the
     * result. Returns true when the category is now part of the filter; an empty filter
     * lets every category through.
     */
    public boolean toggle(String category) {
        String key = normalize(category);
        boolean added = categories.add(key);
        if (!added) {
            categories.remove(key);
        }
        persist();
        return added;
    }

    /** Sets the verbosity threshold and persists it; {@code OFF} silences everything. */
    public void setLevel(Level level) {
        this.level = level == null ? Level.OFF : level;
        persist();
    }

    /** Current verbosity threshold. */
    public Level level() {
        return level;
    }

    private boolean infoEnabled() {
        return enabled && level.ordinal() >= Level.INFO.ordinal();
    }

    private void print(String prefix, String message) {
        Bukkit.getLogger().info(prefix + message);
    }

    private void persist() {
        if (storage == null) {
            return;
        }
        List<String> sorted = new ArrayList<>(categories);
        Collections.sort(sorted);
        storage.set(KEY_ENABLED, enabled);
        storage.set(KEY_LEVEL, level.name());
        storage.set(KEY_CATEGORIES, sorted);
        storage.save();
    }

    private Level parseLevel(String raw) {
        try {
            return Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Valor invalido en " + KEY_LEVEL + ": '" + raw
                    + "', usando " + Level.DEBUG.name());
            return Level.DEBUG;
        }
    }

    private static String normalize(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }
}
