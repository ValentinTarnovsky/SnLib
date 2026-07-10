package com.sn.lib.papi.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * Lazy PlaceholderAPI isolation layer of one consumer context.
 *
 * <p>Every bytecode reference to a PAPI class lives in the nested {@code Bridge} and
 * {@code BuiltExpansion} classes, loaded only after the presence probe succeeds; the
 * outer class holds no PAPI reference, so a server without PlaceholderAPI never triggers
 * {@link NoClassDefFoundError}. The presence flag is probed lazily, cached, and dropped
 * via {@link #invalidate()} when the target plugin toggles.</p>
 */
public final class PapiHolder {

    private final JavaPlugin owner;
    private final List<Object> registered = new CopyOnWriteArrayList<>();

    private volatile @Nullable Boolean present;

    public PapiHolder(JavaPlugin owner) {
        this.owner = owner;
    }

    /** True when the PlaceholderAPI plugin is present and enabled; probed lazily, cached. */
    public boolean available() {
        Boolean cached = present;
        if (cached == null) {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            cached = papi != null && papi.isEnabled();
            present = cached;
        }
        return cached;
    }

    /** Drops the cached presence flag; the next call probes again. */
    public void invalidate() {
        present = null;
    }

    /** Resolves PAPI tokens, or returns the text intact when PAPI is unavailable. */
    public String apply(@Nullable OfflinePlayer player, String text) {
        if (!available()) {
            return text;
        }
        try {
            return Bridge.setPlaceholders(player, text);
        } catch (LinkageError e) {
            markBroken(e);
            return text;
        }
    }

    /**
     * Registers a declarative expansion, unregistering any previous one with the same
     * identifier first (lookup-before-register: a second enable of the consumer never
     * fails). The instance is tracked for {@link #unregisterAll()}.
     */
    public boolean register(String identifier, String author, String version,
            Map<String, Function<OfflinePlayer, String>> exact,
            Map<String, BiFunction<OfflinePlayer, String, String>> prefixed) {
        if (!available()) {
            owner.getLogger().warning("PlaceholderAPI ausente: expansion '" + identifier
                    + "' no registrada");
            return false;
        }
        try {
            Object expansion = Bridge.register(owner, identifier, author, version, exact, prefixed);
            if (expansion == null) {
                owner.getLogger().warning("PlaceholderAPI rechazo la expansion '" + identifier + "'");
                return false;
            }
            registered.add(expansion);
            return true;
        } catch (LinkageError e) {
            markBroken(e);
            return false;
        }
    }

    /** Unregisters every expansion registered through this holder (context teardown). */
    public void unregisterAll() {
        if (registered.isEmpty()) {
            return;
        }
        List<Object> drained = new ArrayList<>(registered);
        registered.clear();
        try {
            for (Object expansion : drained) {
                Bridge.unregister(expansion);
            }
        } catch (LinkageError e) {
            markBroken(e);
        }
    }

    private void markBroken(LinkageError e) {
        present = Boolean.FALSE;
        owner.getLogger().warning("PlaceholderAPI inaccesible (" + e + "); modulo papi degradado");
    }

    /**
     * Together with {@code BuiltExpansion}, the only class whose constant pool references
     * PAPI types; loaded exclusively behind a successful presence probe.
     */
    private static final class Bridge {

        private Bridge() {
        }

        static String setPlaceholders(@Nullable OfflinePlayer player, String text) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }

        static @Nullable Object register(JavaPlugin owner, String identifier, String author,
                String version, Map<String, Function<OfflinePlayer, String>> exact,
                Map<String, BiFunction<OfflinePlayer, String, String>> prefixed) {
            PlaceholderExpansion existing = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager().getExpansion(identifier.toLowerCase(Locale.ROOT));
            if (existing != null) {
                existing.unregister();
            }
            BuiltExpansion expansion =
                    new BuiltExpansion(owner, identifier, author, version, exact, prefixed);
            return expansion.register() ? expansion : null;
        }

        static void unregister(Object expansion) {
            ((PlaceholderExpansion) expansion).unregister();
        }
    }

    /**
     * Expansion built from the declarative resolver maps: persists across PlaceholderAPI
     * expansion reloads and null-checks the requesting player before any resolver runs.
     */
    private static final class BuiltExpansion extends PlaceholderExpansion {

        private final JavaPlugin owner;
        private final String identifier;
        private final String author;
        private final String version;
        private final Map<String, Function<OfflinePlayer, String>> exact;
        private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed;

        BuiltExpansion(JavaPlugin owner, String identifier, String author, String version,
                Map<String, Function<OfflinePlayer, String>> exact,
                Map<String, BiFunction<OfflinePlayer, String, String>> prefixed) {
            this.owner = owner;
            this.identifier = identifier;
            this.author = author;
            this.version = version;
            this.exact = exact;
            this.prefixed = prefixed;
        }

        @Override
        public @NotNull String getIdentifier() {
            return identifier;
        }

        @Override
        public @NotNull String getAuthor() {
            return author;
        }

        @Override
        public @NotNull String getVersion() {
            return version;
        }

        /** Survives PlaceholderAPI expansion reloads; removed only by the context teardown. */
        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
            if (player == null) {
                return null;
            }
            String key = params.toLowerCase(Locale.ROOT);
            Function<OfflinePlayer, String> exactResolver = exact.get(key);
            if (exactResolver != null) {
                return resolveSafe(params, () -> exactResolver.apply(player));
            }
            for (Map.Entry<String, BiFunction<OfflinePlayer, String, String>> entry
                    : prefixed.entrySet()) {
                if (key.startsWith(entry.getKey())) {
                    String arg = params.substring(entry.getKey().length());
                    return resolveSafe(params, () -> entry.getValue().apply(player, arg));
                }
            }
            return null;
        }

        private @Nullable String resolveSafe(String params, Supplier<String> resolver) {
            try {
                return resolver.get();
            } catch (Throwable t) {
                owner.getLogger().warning("Placeholder '%" + identifier + "_" + params
                        + "%' fallo al resolver: " + t);
                return null;
            }
        }
    }
}
