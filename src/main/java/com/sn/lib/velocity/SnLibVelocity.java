package com.sn.lib.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

/**
 * Velocity entry point of SnLib. The SAME {@code SnLib.jar} is a Paper plugin (plugin.yml)
 * AND a Velocity plugin (velocity-plugin.json); on Velocity only {@code com.sn.lib.velocity.*}
 * and the platform-neutral text pipeline load, never the Bukkit-bound classes (lazy
 * per-platform class loading).
 *
 * <p>This class is just the dependency anchor: consumer proxy plugins declare
 * {@code "dependencies":[{"id":"snlib"}]} in their velocity-plugin.json and build a
 * {@link Snv} context in their own init. SnLib on Velocity is a small base for homogeneity
 * (config, text, scheduler, commands) - it holds no cross-server state.</p>
 *
 * <p>The plugin metadata lives in the hand-written {@code velocity-plugin.json} (so Maven
 * filters {@code ${project.version}} into it); the {@code @Plugin} annotation is deliberately
 * omitted to avoid the annotation processor emitting a second, unfiltered descriptor.</p>
 */
public final class SnLibVelocity {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public SnLibVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        logger.info("SnLib (Velocity base) enabled on {} backends.",
                proxy.getAllServers().size());
    }
}
