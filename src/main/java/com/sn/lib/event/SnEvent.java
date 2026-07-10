package com.sn.lib.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Self-firing cancellable base for custom library events.
 *
 * <p>Concrete subclasses fire themselves via {@link #call()} and must still supply the
 * Bukkit handler-list pair (the {@code getHandlers()} instance method plus the static
 * {@code getHandlerList()}).</p>
 */
public abstract class SnEvent extends Event implements Cancellable {

    private boolean cancelled;

    protected SnEvent() {
    }

    protected SnEvent(boolean async) {
        super(async);
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
