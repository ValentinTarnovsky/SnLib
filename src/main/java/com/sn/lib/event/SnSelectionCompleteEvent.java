package com.sn.lib.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import com.sn.lib.region.Cuboid;

/**
 * Fired when a player completes a cuboid selection (both positions set in the same world
 * and within the spec's volume cap).
 *
 * <p>Synchronous, main thread, dispatched via {@link #call()}. Cancellation is BINDING:
 * when a listener cancels it, the spec's {@code onSelect} callback does not run and the
 * session stays alive, so the player can re-click. This lets protection or staff plugins
 * veto selections owned by other consumers.</p>
 *
 * <p>This is the ONLY event of the selection module by design: there is no
 * SnSelectionUpdateEvent because each wand click would fire a server-wide event; the
 * {@code onUpdate} callback of the spec covers that case with zero global cost (an update
 * event can be added later, additively, if the need appears).</p>
 */
public final class SnSelectionCompleteEvent extends SnPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin owner;
    private final String specId;
    private final Cuboid cuboid;

    /**
     * @param player player who completed the selection
     * @param owner  consumer plugin owning the selection manager
     * @param specId id of the {@code SelectionSpec} the session was opened with
     * @param cuboid completed cuboid (immutable, safe to share without copying)
     */
    public SnSelectionCompleteEvent(Player player, Plugin owner, String specId, Cuboid cuboid) {
        super(player);
        this.owner = owner;
        this.specId = specId;
        this.cuboid = cuboid;
    }

    /** Consumer plugin owning the selection. */
    public Plugin owner() {
        return owner;
    }

    /** Id of the selection spec. */
    public String specId() {
        return specId;
    }

    /** Completed cuboid (immutable, safe to share without copying). */
    public Cuboid cuboid() {
        return cuboid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
