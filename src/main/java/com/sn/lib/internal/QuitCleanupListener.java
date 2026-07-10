package com.sn.lib.internal;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import com.sn.lib.tenant.OwnedHolder;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.tenant.internal.TenantSweeper;

/**
 * The ONLY PlayerQuitEvent/PlayerKickEvent listener of the whole library, owned by SnLib.
 *
 * <p>Modules register per-owner cleanup callbacks via {@link #register}; on quit or kick
 * the listener force-closes the player's open inventory when its holder is a library
 * {@link OwnedHolder} and then runs every owner's callbacks with the player's UUID. A
 * kicked player fires both kick and quit, so callbacks must be idempotent.</p>
 */
public final class QuitCleanupListener implements Listener {

    /** Server-wide static justified: quit-cleanup callbacks keyed per owning plugin. */
    private static final TenantRegistry<Consumer<UUID>> CALLBACKS = new TenantRegistry<>();

    /** Registers a callback run with the quitting player's UUID; swept per owner. */
    public static void register(Plugin owner, Consumer<UUID> callback) {
        CALLBACKS.add(owner, callback);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer());
    }

    private void cleanup(Player player) {
        closeLibraryInventory(player);
        UUID uuid = player.getUniqueId();
        CALLBACKS.forEachOwner((owner, callbacks) -> {
            for (Consumer<UUID> callback : callbacks) {
                try {
                    callback.accept(uuid);
                } catch (Throwable t) {
                    owner.getLogger().warning("Callback de quit-cleanup fallo: " + t);
                }
            }
        });
    }

    /**
     * Forces the close when the player is viewing a library inventory. The check walks
     * the tracked open {@link OwnedHolder}s and their viewers instead of the player's
     * open-view API, whose view type is binary-incompatible across the 1.20.4/1.21
     * boundary and is banned from the codebase.
     */
    private static void closeLibraryInventory(Player player) {
        boolean[] viewing = new boolean[1];
        TenantSweeper.forEachOpenInventory((OwnedHolder holder) -> {
            try {
                if (!viewing[0] && holder.getInventory().getViewers().contains(player)) {
                    viewing[0] = true;
                }
            } catch (Throwable t) {
                holder.owner().getLogger()
                        .warning("No se pudo inspeccionar un inventario de la lib: " + t);
            }
        });
        if (viewing[0]) {
            player.closeInventory();
        }
    }
}
