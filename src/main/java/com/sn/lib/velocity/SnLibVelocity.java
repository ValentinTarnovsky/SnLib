package com.sn.lib.velocity;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.sn.lib.SnExperimental;
import com.sn.lib.velocity.internal.ProxyBridgeRuntime;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Velocity bootstrap of the SAME SnLib.jar: the descriptor is the hand-written
 * velocity-plugin.json (id {@code snlib}), so the jar drops into the proxy's plugins/
 * folder exactly like it does on every backend. Consumer proxy plugins declare
 * {@code "dependencies": [{"id": "snlib"}]} and reach the bridge through
 * {@link SnProxy}.
 *
 * <p>Class-loading discipline: this class and everything under com.sn.lib.velocity may
 * ONLY touch {@code com.sn.lib.bridge.wire} (platform-neutral), the platform-pure
 * bridge records (SnDelivery et al) and Velocity API; a Bukkit reference here would
 * crash the proxy classloader, and a CI bytecode scan enforces it.</p>
 */
@SnExperimental
public final class SnLibVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public SnLibVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String version = proxy.getPluginManager().getPlugin("snlib")
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("desconocida");
        ProxyBridgeRuntime.init(this, proxy, logger, dataDirectory, version);
        registerStatusCommand();
        logger.info("SnLib {} habilitado en Velocity (SnBridge proxy side{})", version,
                ProxyBridgeRuntime.get().available() ? "" : ", SIN secreto HMAC: bridge apagado");
    }

    /** The runbook's operator surface on the proxy: {@code /snlibv status}. */
    private void registerStatusCommand() {
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("snlibv").plugin(this).build(),
                new com.velocitypowered.api.command.SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        for (String line : SnProxy.statusReport().split("\n")) {
                            invocation.source().sendPlainMessage(line);
                        }
                    }

                    @Override
                    public boolean hasPermission(Invocation invocation) {
                        return invocation.source().hasPermission("snlib.admin.bridge");
                    }
                });
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ProxyBridgeRuntime.shutdownRuntime();
    }
}
