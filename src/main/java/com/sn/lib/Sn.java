package com.sn.lib;

import org.bukkit.plugin.java.JavaPlugin;

import com.sn.lib.scheduler.SnScheduler;

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
    private final SnScheduler scheduler;

    Sn(JavaPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = new SnScheduler(plugin);
    }

    /** Consumer plugin that owns this context. */
    public JavaPlugin plugin() {
        return plugin;
    }

    /** Folia-aware scheduler bound to the owning plugin; available in every context. */
    public SnScheduler scheduler() {
        return scheduler;
    }

    /** Shuts down every module owned by this context and releases its registrations. */
    public void shutdown() {
    }

    /** Reloads every reloadable module owned by this context. */
    public void reloadAll() {
    }
}
