package com.sn.lib.event;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player crosses from one chunk to another through movement.
 *
 * <p>Synthesized by the library's shared chunk-move listener from
 * {@code PlayerMoveEvent}. Cancellation is BINDING: cancelling this event cancels the
 * source {@code PlayerMoveEvent} (same pattern as the DISPENSER vector of
 * {@link SnArmourEquipEvent}).</p>
 *
 * <p>Scope: only movement emits this event. Teleports, joins and respawns do NOT
 * ({@code PlayerTeleportEvent} has its own handler list and never passes through the
 * move handler).</p>
 */
public final class SnChunkMoveEvent extends SnPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location from;
    private final Location to;

    /**
     * @param player player crossing the chunk border
     * @param from   location the move started from
     * @param to     location the move ends at
     *
     * <p>Both locations are cloned into event-owned snapshots: mutating them never
     * affects the source {@code PlayerMoveEvent}.</p>
     */
    public SnChunkMoveEvent(Player player, Location from, Location to) {
        super(player);
        this.from = from.clone();
        this.to = to.clone();
    }

    /** Snapshot of the location the move started from. */
    public Location fromLocation() {
        return from;
    }

    /** Snapshot of the location the move ends at. */
    public Location toLocation() {
        return to;
    }

    /**
     * Chunk the player is leaving, resolved from the origin location on call.
     * In practice always loaded: the player just came from it.
     */
    public Chunk fromChunk() {
        return from.getChunk();
    }

    /**
     * Chunk the player is entering, resolved from the destination location on call.
     * In practice always loaded: the player is standing in it.
     */
    public Chunk toChunk() {
        return to.getChunk();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
