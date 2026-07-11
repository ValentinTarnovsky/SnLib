package com.sn.lib.region;

import java.util.UUID;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.scheduler.TaskHandle;

/**
 * Mutable per-player selection state, owned by its {@link SelectionManager}.
 *
 * <p>Main-thread only: positions are mutated by the wand listener and the programmatic
 * setters and read by the synchronous renderer, all on the main thread. A session is
 * NEVER persisted to disk (it is transient player state); to persist a result use
 * {@link Cuboid#serialize()}.</p>
 */
public final class SelectionSession {

    private final UUID playerId;
    private final SelectionSpec spec;
    private final SelectionManager manager;

    /** Repeating render task of this session, armed by the manager; null while idle. */
    @Nullable TaskHandle renderTask;

    /** Creation instant; the renderer evaluates the spec timeout against it. */
    final long createdAtMillis;

    @Nullable Location pos1;
    @Nullable Location pos2;

    SelectionSession(UUID playerId, SelectionSpec spec, SelectionManager manager) {
        this.playerId = playerId;
        this.spec = spec;
        this.manager = manager;
        this.createdAtMillis = System.currentTimeMillis();
    }

    /** UUID of the selecting player. */
    public UUID playerId() {
        return playerId;
    }

    /** Spec this session was opened with. */
    public SelectionSpec spec() {
        return spec;
    }

    /** Defensive copy of position 1, or null when unset. */
    public @Nullable Location pos1() {
        return pos1 == null ? null : pos1.clone();
    }

    /** Defensive copy of position 2, or null when unset. */
    public @Nullable Location pos2() {
        return pos2 == null ? null : pos2.clone();
    }

    /**
     * Sets position 1 programmatically, with the SAME consequences as a wand click:
     * renderer refresh, messages, onUpdate and the completion pipeline (event plus
     * onSelect). A null location clears the position.
     */
    public void setPos1(@Nullable Location pos) {
        manager.applyPos(this, true, pos);
    }

    /** Sets position 2 programmatically; same contract as {@link #setPos1(Location)}. */
    public void setPos2(@Nullable Location pos) {
        manager.applyPos(this, false, pos);
    }

    /**
     * True when both positions are set in the SAME world, matched by name; a position
     * without a loaded world counts as no world and yields false.
     */
    public boolean hasBothPositions() {
        return pos1 != null && pos2 != null
                && pos1.isWorldLoaded() && pos2.isWorldLoaded()
                && pos1.getWorld().getName().equals(pos2.getWorld().getName());
    }

    /** Cuboid of the two positions when {@link #hasBothPositions()}, or null. */
    public @Nullable Cuboid cuboid() {
        return hasBothPositions() ? Cuboid.of(pos1, pos2) : null;
    }

    /** Clears both positions and cuts the renderer; the session stays alive. */
    public void clearPositions() {
        pos1 = null;
        pos2 = null;
        manager.refreshRenderer(this);
    }
}
