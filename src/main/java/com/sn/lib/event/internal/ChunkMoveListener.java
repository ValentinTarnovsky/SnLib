package com.sn.lib.event.internal;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import com.sn.lib.event.SnChunkMoveEvent;

/**
 * Shared listener owned by SnLib that synthesizes {@link SnChunkMoveEvent}.
 *
 * <p>Hot-path quick-exit without allocations: same-chunk moves (the overwhelming
 * majority) return before anything is created. Worlds are compared by identity because
 * Bukkit {@code World} instances are per-server singletons. Runs at default (NORMAL)
 * priority on purpose so a binding cancellation can apply before MONITOR observers.
 * Inscribed in the ListenerHub; the registerEvents call happens UNIQUELY in the
 * SnLibPlugin bootstrap.</p>
 */
public final class ChunkMoveListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) {
            return;
        }
        if (!new SnChunkMoveEvent(event.getPlayer(), from, to).call()) {
            event.setCancelled(true);
        }
    }
}
