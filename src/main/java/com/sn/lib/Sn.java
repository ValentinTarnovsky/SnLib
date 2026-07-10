package com.sn.lib;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Per-plugin SnLib context: the handle through which a consumer reaches every module
 * it declared in its {@link SnSpec}.
 *
 * <p>Module accessor policy: each typed accessor (scheduler, yml, lang, guis, items, db,
 * papi, ...) only works if the corresponding module was declared in the owning spec; an
 * accessor for an undeclared module throws {@link UnsupportedOperationException} naming
 * the missing {@code SnSpec.builder()} call.</p>
 */
public final class Sn {

    private final JavaPlugin plugin;

    Sn(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Consumer plugin that owns this context. */
    public JavaPlugin plugin() {
        return plugin;
    }

    /** Shuts down every module owned by this context and releases its registrations. */
    public void shutdown() {
    }

    /** Reloads every reloadable module owned by this context. */
    public void reloadAll() {
    }
}
