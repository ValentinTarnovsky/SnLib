package com.sn.lib.tenant.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import com.sn.lib.SnLibPlugin;
import com.sn.lib.db.PlayerDataCache;
import com.sn.lib.event.internal.ArmourEquipListener;
import com.sn.lib.event.internal.ChunkMoveListener;
import com.sn.lib.gui.internal.GuiClickListener;
import com.sn.lib.gui.internal.GuiProtectionListener;
import com.sn.lib.hologram.internal.HologramChunkListener;
import com.sn.lib.hook.HookListener;
import com.sn.lib.hook.SoftDependency;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.item.internal.ItemInteractListener;
import com.sn.lib.item.internal.ItemPropertyListener;
import com.sn.lib.item.internal.LockedItemListener;
import com.sn.lib.region.internal.SelectionWandListener;
import com.sn.lib.teleport.internal.TeleportDamageListener;
import com.sn.lib.teleport.internal.TeleportMoveListener;
import com.sn.lib.update.UpdateChecker;

/**
 * Single inscription point for every shared listener of the library.
 *
 * <p>Fixed wiring mechanism: each module inscribes its shared listener here (accumulated
 * in a list from the static initializer, before any bootstrap call) and
 * {@link #registerAll} performs the ONLY {@code registerEvents} call of the whole
 * library, invoked once from the SnLibPlugin bootstrap. No library code may register
 * events anywhere else.</p>
 */
public final class ListenerHub {

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static {
        inscribe(new HookListener(SoftDependency::forEachRegistered));
        inscribe(new TenantSweeper());
        inscribe(new QuitCleanupListener());
        inscribe(new ArmourEquipListener());
        inscribe(new ChunkMoveListener());
        inscribe(new ItemPropertyListener());
        inscribe(new ItemInteractListener());
        inscribe(new LockedItemListener());
        inscribe(new GuiClickListener());
        inscribe(new GuiProtectionListener());
        inscribe(PlayerDataCache.joinListener());
        inscribe(UpdateChecker.joinListener());
        inscribe(new HologramChunkListener());
        inscribe(new SelectionWandListener());
        inscribe(new TeleportMoveListener());
        inscribe(new TeleportDamageListener());
    }

    private ListenerHub() {
    }

    /** Adds a shared listener to the hub; it stays dormant until {@link #registerAll}. */
    public static void inscribe(Listener listener) {
        LISTENERS.add(listener);
    }

    /**
     * Registers every inscribed listener against the SnLib plugin: the single
     * {@code registerEvents} point of the library, invoked once from
     * {@code SnLibPlugin.onEnable}. Idempotent: SnLib's previous event registrations
     * are dropped first, so a double call or a re-enable never duplicates handlers
     * (a disable of SnLib also unregisters them all).
     */
    public static void registerAll(SnLibPlugin plugin) {
        HandlerList.unregisterAll(plugin);
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        for (Listener listener : LISTENERS) {
            pluginManager.registerEvents(listener, plugin);
        }
    }
}
