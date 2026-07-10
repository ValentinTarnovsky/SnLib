package com.sn.lib.hook;

import java.util.function.Consumer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Shared listener that activates/deactivates every registered {@link SoftDependency} live
 * when its target plugin enables or disables.
 *
 * <p>The iteration source is injected (unit-testable without a registry); step 16 bridges
 * it to {@code TenantRegistry.forEachOwner} over the per-owner SoftDependency registry.</p>
 *
 * <p><b>Wiring note (literal):</b> this listener is defined here unit-testable; it is
 * INSCRIBED into the ListenerHub only in step 16 (which retro-inscribes it), and the
 * {@code registerEvents} call happens UNIQUELY in the SnLibPlugin bootstrap of step 31.
 * Never register it anywhere else.</p>
 */
public final class HookListener implements Listener {

    private final Consumer<Consumer<SoftDependency<?>>> forEachDependency;

    /**
     * @param forEachDependency applies an action to every registered SoftDependency of
     *                          every owner
     */
    public HookListener(Consumer<Consumer<SoftDependency<?>>> forEachDependency) {
        this.forEachDependency = forEachDependency;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        forEachDependency.accept(dependency -> {
            if (dependency.pluginName().equalsIgnoreCase(name)) {
                dependency.refresh();
            }
        });
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        forEachDependency.accept(dependency -> {
            if (dependency.pluginName().equalsIgnoreCase(name)) {
                dependency.deactivate();
            }
        });
    }
}
