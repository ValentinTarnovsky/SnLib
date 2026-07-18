package com.sn.lib.teleport.internal;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.teleport.Teleports;

/**
 * Single shared listener owned by SnLib that cancels a pending warmup teleport when the
 * player moves. Inscribed in the ListenerHub, which performs the single event registration
 * of the whole library from the SnLibPlugin bootstrap; this class never registers itself.
 *
 * <p>Hot-path contract (this listener sees every move event of the server): a block-delta
 * quick exit returns before any work when the player only rotated its head or moved within
 * the same block, the overwhelming majority of move events. Only an actual block-position
 * change reaches {@link Teleports#dispatchMove}, which acts solely for contexts that
 * declared the teleport module (no declared module, no manager, nothing runs). Observes at
 * MONITOR with {@code ignoreCancelled = true}: a move another plugin cancelled did not
 * happen, so it must not cancel the teleport.</p>
 */
public final class TeleportMoveListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (blockUnchanged(event.getFrom(), event.getTo())) {
            return;
        }
        Teleports.dispatchMove(event.getPlayer());
    }

    /**
     * Skip condition of the listener: true when the move keeps the player in the same block
     * (head rotation or sub-block movement) or has no destination. Folds the two locations
     * into the pure {@link #sameBlock} block compare.
     */
    static boolean blockUnchanged(Location from, @Nullable Location to) {
        return to == null || sameBlock(from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ());
    }

    /**
     * True when both positions occupy the same block: pure integer comparison, the core of
     * the listener's quick exit so head rotation and sub-block movement never cancel a
     * teleport. Extracted for unit coverage without a server.
     */
    public static boolean sameBlock(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return fromX == toX && fromY == toY && fromZ == toZ;
    }
}
