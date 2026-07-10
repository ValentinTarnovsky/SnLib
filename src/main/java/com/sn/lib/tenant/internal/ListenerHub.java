package com.sn.lib.tenant.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import com.sn.lib.SnLibPlugin;
import com.sn.lib.event.internal.ArmourEquipListener;
import com.sn.lib.hook.HookListener;
import com.sn.lib.hook.SoftDependency;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.item.internal.ItemPropertyListener;

/**
 * Single inscription point for every shared listener of the library.
 *
 * <p>Fixed wiring mechanism: each module inscribes its shared listener here (accumulated
 * in a list) and {@link #registerAll} performs the ONLY {@code registerEvents} call of
 * the whole library, invoked once from the SnLibPlugin bootstrap. No library code may
 * register events anywhere else.</p>
 */
public final class ListenerHub {

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static {
        inscribe(new HookListener(SoftDependency::forEachRegistered));
        inscribe(new TenantSweeper());
        inscribe(new QuitCleanupListener());
        inscribe(new ArmourEquipListener());
        inscribe(new ItemPropertyListener());
    }

    private ListenerHub() {
    }

    /** Adds a shared listener to the hub; it stays dormant until {@link #registerAll}. */
    public static void inscribe(Listener listener) {
        LISTENERS.add(listener);
    }

    /**
     * Registers every inscribed listener against the SnLib plugin. Bootstrap-only call;
     * a disable of SnLib unregisters them, so a re-enable registers them again.
     */
    public static void registerAll(SnLibPlugin plugin) {
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        for (Listener listener : LISTENERS) {
            pluginManager.registerEvents(listener, plugin);
        }
    }
}
