package com.sn.lib.tenant.internal;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLib;
import com.sn.lib.SnLibPlugin;
import com.sn.lib.compat.SnVersion;
import com.sn.lib.hook.SoftDependency;
import com.sn.lib.tenant.OwnedHolder;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Shared double-net listener owned by SnLib: when a consumer disables, every per-owner
 * registration of EXCLUSIVELY that owner is swept (no-interference), its open library
 * inventories are closed and its context key is removed; when SnLib itself disables, the
 * full cascade shuts down every live context in reverse registration order.
 *
 * <p>Bukkit fires {@link PluginDisableEvent} BEFORE the plugin's own {@code onDisable},
 * so the per-consumer net is deferred one tick: a graceful teardown (the consumer's
 * onDisable calling {@code Sn.shutdown()}) runs first on live modules and the deferred
 * pass only mops up what the owner left behind, warning when it had to force a shutdown.
 * When the scheduler is unavailable (server shutdown), leftovers are caught by the SnLib
 * cascade, which runs after every hard-depending consumer already disabled.</p>
 */
public final class TenantSweeper implements Listener {

    /** Context-registry access installed by SnLib's static initializer. */
    public interface ContextAccess {

        /** Removes the owner's context key only if it still maps to {@code expected}. */
        boolean detach(Plugin owner, Sn expected);

        /** Removes and returns every context, in reverse registration order. */
        List<Sn> detachAllReversed();
    }

    private static volatile @Nullable ContextAccess contexts;

    /**
     * Server-wide static justified: registry of open library inventories, keyed per
     * owning plugin. The GUI module tracks its {@link OwnedHolder}s here; sweeping an
     * owner closes them through the sweep callback.
     */
    private static final TenantRegistry<OwnedHolder> OPEN_HOLDERS =
            new TenantRegistry<>(TenantSweeper::closeHolder);

    /** Installs the access to SnLib's context registry; called from SnLib's static init. */
    public static void bindContexts(ContextAccess access) {
        contexts = access;
    }

    /** Tracks an open library inventory so a disable of its owner closes it. */
    public static void trackInventory(OwnedHolder holder) {
        OPEN_HOLDERS.add(holder.owner(), holder);
    }

    /** Stops tracking a library inventory once it is closed. */
    public static void untrackInventory(OwnedHolder holder) {
        OPEN_HOLDERS.remove(holder.owner(), holder);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        if (plugin instanceof SnLibPlugin) {
            cascade();
            return;
        }
        Sn ctx = plugin instanceof JavaPlugin owner ? SnLib.context(owner) : null;
        if (ctx == null) {
            return;
        }
        defer(() -> sweep(plugin, ctx));
    }

    private void sweep(Plugin owner, Sn captured) {
        ContextAccess access = contexts;
        if (access == null) {
            return;
        }
        Sn current = owner instanceof JavaPlugin javaPlugin ? SnLib.context(javaPlugin) : null;
        if (current != null && current != captured) {
            return;
        }
        if (current != null && !current.isShuttingDown()) {
            owner.getLogger().warning(
                    "Contexto SnLib no cerrado en onDisable; shutdown forzado por el sweeper (doble red)");
            shutdownQuietly(current);
        }
        OPEN_HOLDERS.removeOwner(owner);
        TenantRegistry.sweepOwner(owner);
        SoftDependency.targetDisabled(owner.getName());
        access.detach(owner, captured);
    }

    private void cascade() {
        ContextAccess access = contexts;
        if (access == null) {
            return;
        }
        for (Sn ctx : access.detachAllReversed()) {
            Plugin owner = ctx.plugin();
            if (!ctx.isShuttingDown() && !(owner instanceof SnLibPlugin)) {
                owner.getLogger().warning(
                        "Contexto SnLib no cerrado en onDisable; shutdown forzado por el sweeper (doble red)");
            }
            shutdownQuietly(ctx);
            OPEN_HOLDERS.removeOwner(owner);
            TenantRegistry.sweepOwner(owner);
        }
    }

    private static void shutdownQuietly(Sn ctx) {
        try {
            ctx.shutdown();
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Shutdown del contexto fallo: " + t);
        }
    }

    private static void closeHolder(OwnedHolder holder) {
        try {
            for (HumanEntity viewer : List.copyOf(holder.getInventory().getViewers())) {
                viewer.closeInventory();
            }
        } catch (Throwable t) {
            holder.owner().getLogger().warning("No se pudo cerrar un inventario de la lib: " + t);
        }
    }

    private static void defer(Runnable task) {
        try {
            JavaPlugin self = JavaPlugin.getProvidingPlugin(SnLibPlugin.class);
            if (!self.isEnabled()) {
                return;
            }
            if (SnVersion.isFolia()) {
                Bukkit.getGlobalRegionScheduler().run(self, scheduled -> task.run());
            } else {
                Bukkit.getScheduler().runTask(self, task);
            }
        } catch (Throwable t) {
            // Scheduler unavailable (shutdown race): the SnLib cascade catches the leftovers.
        }
    }
}
