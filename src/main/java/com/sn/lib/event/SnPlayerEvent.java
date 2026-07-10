package com.sn.lib.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerEvent;

/**
 * Self-firing cancellable base for custom library events that always carry a player.
 *
 * <p>Same contract as {@link SnEvent}: subclasses fire themselves via {@link #call()} and
 * must supply the Bukkit handler-list pair.</p>
 */
public abstract class SnPlayerEvent extends PlayerEvent implements Cancellable {

    private boolean cancelled;

    protected SnPlayerEvent(Player who) {
        super(who);
    }

    protected SnPlayerEvent(Player who, boolean async) {
        super(who, async);
    }

    /** Dispatches this event through the plugin manager and reports whether it survived. */
    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return !isCancelled();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
