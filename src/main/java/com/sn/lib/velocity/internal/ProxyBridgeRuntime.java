package com.sn.lib.velocity.internal;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.sn.lib.bridge.wire.HmacSigner;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;

/**
 * Server-wide bridge runtime of the PROXY side, owned by the SnLibVelocity bootstrap.
 * Holds the HMAC signer, the shared msgId counter, the namespace map, the channel
 * registrar entries and the ONE {@link PluginMessageEvent} listener of the whole bridge.
 *
 * <p>Security invariants live here: EVERY {@code snlib:*} channel is marked handled
 * unconditionally (claimed or not, either direction), so bridge traffic is never
 * forwarded to a client and client-spoofed frames never reach a backend; only messages
 * whose source is a {@link ServerConnection} are processed at all. Legacy channels
 * claimed via detectLegacy are also sunk and counted.</p>
 */
public final class ProxyBridgeRuntime {

    private static volatile @Nullable ProxyBridgeRuntime instance;

    private final Object bootstrap;
    private final ProxyServer proxy;
    private final Logger logger;
    private final @Nullable HmacSigner signer;
    private final String libVersion;
    private final AtomicInteger msgIds = new AtomicInteger(new SecureRandom().nextInt());
    private final Map<String, ProxyChannelCore> byNamespace = new HashMap<>(8);
    private final Map<String, ProxyChannelCore> byChannelName = new HashMap<>(8);
    private final Map<String, ProxyChannelCore> legacyChannels = new HashMap<>(4);
    private final ScheduledTask sweeper;

    private ProxyBridgeRuntime(Object bootstrap, ProxyServer proxy, Logger logger,
            Path dataDirectory, String libVersion) {
        this.bootstrap = bootstrap;
        this.proxy = proxy;
        this.logger = logger;
        this.libVersion = libVersion;
        byte[] secret = ProxySecretResolver.resolve(Path.of("."), dataDirectory, logger);
        this.signer = secret == null ? null : new HmacSigner(secret);
        this.sweeper = proxy.getScheduler().buildTask(bootstrap, this::sweepAll)
                .repeat(1L, TimeUnit.SECONDS).schedule();
        proxy.getEventManager().register(bootstrap, this);
    }

    /** Called once from SnLibVelocity on ProxyInitializeEvent. */
    public static void init(Object bootstrap, ProxyServer proxy, Logger logger,
            Path dataDirectory, String libVersion) {
        instance = new ProxyBridgeRuntime(bootstrap, proxy, logger, dataDirectory, libVersion);
    }

    /** Called from SnLibVelocity on ProxyShutdownEvent. */
    public static void shutdownRuntime() {
        ProxyBridgeRuntime runtime = instance;
        if (runtime == null) {
            return;
        }
        instance = null;
        runtime.sweeper.cancel();
        synchronized (runtime) {
            for (ProxyChannelCore core : runtime.byNamespace.values()) {
                core.teardown();
            }
            runtime.byNamespace.clear();
            runtime.byChannelName.clear();
            runtime.legacyChannels.clear();
        }
    }

    public static ProxyBridgeRuntime get() {
        ProxyBridgeRuntime runtime = instance;
        if (runtime == null) {
            throw new IllegalStateException(
                    "SnBridge no inicializado en el proxy: falta SnLib.jar en plugins/ de Velocity");
        }
        return runtime;
    }

    public static @Nullable ProxyBridgeRuntime live() {
        return instance;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public boolean available() {
        return signer != null;
    }

    // -------------------------------------------------------
    // Claims
    // -------------------------------------------------------

    /** Claims a namespace on the proxy side (first-claim-wins, idempotent per namespace). */
    public synchronized ProxyChannelCore claim(String namespace, int msgset,
            ProxyChannelCore.Dispatcher dispatcher) {
        ProxyChannelCore existing = byNamespace.get(namespace);
        if (existing != null) {
            return existing;
        }
        String channelName = "snlib:ext/" + namespace;
        ProxyChannelCore core = new ProxyChannelCore(namespace, msgset, libVersion, signer,
                msgIds::incrementAndGet, () -> System.nanoTime() / 1_000_000L,
                sinkFor(channelName), dispatcher,
                new ProxyChannelCore.Log() {
                    @Override
                    public void warn(String message) {
                        logger.warn("[SnBridge] {}", message);
                    }

                    @Override
                    public void debug(java.util.function.Supplier<String> message) {
                        // Velocity: sin SnDebug; el nivel debug del logger del proxy decide
                        if (logger.isDebugEnabled()) {
                            logger.debug("[SnBridge] {}", message.get());
                        }
                    }
                },
                30_000L, 256, 8 * 1024 * 1024, 8);
        byNamespace.put(namespace, core);
        byChannelName.put(channelName, core);
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(channelName));
        return core;
    }

    /** Registers a legacy channel for outdated-counterpart detection (sunk + counted). */
    public synchronized void registerLegacy(String legacyChannel, ProxyChannelCore core) {
        legacyChannels.put(legacyChannel, core);
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(legacyChannel));
    }

    private ProxyChannelCore.CarrierSink sinkFor(String channelName) {
        MinecraftChannelIdentifier identifier = MinecraftChannelIdentifier.from(channelName);
        return (carrier, frames) -> {
            Optional<Player> player = proxy.getPlayer(carrier);
            if (player.isEmpty()) {
                return false;
            }
            Optional<ServerConnection> connection = player.get().getCurrentServer();
            if (connection.isEmpty()) {
                return false;
            }
            boolean allSent = true;
            for (byte[] frame : frames) {
                allSent &= connection.get().sendPluginMessage(identifier, frame);
            }
            return allSent;
        };
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();
        ProxyChannelCore legacyOwner;
        synchronized (this) {
            legacyOwner = legacyChannels.get(channel);
        }
        if (legacyOwner != null) {
            // Canal del stack viejo reclamado via detectLegacy: hundir + contar + avisar
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            legacyOwner.counters().legacyFrame();
            logger.warn("[SnBridge] Trafico en canal legacy '{}': la contraparte backend de '{}'"
                    + " sigue en el stack viejo (actualizarla)", channel, legacyOwner.namespace());
            return;
        }
        if (!channel.startsWith("snlib:")) {
            return;
        }
        // Cualquier canal snlib:* se hunde SIEMPRE, reclamado o no, en ambas direcciones
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return; // frame originado por un cliente: spoof, muere aca
        }
        ProxyChannelCore core;
        synchronized (this) {
            core = byChannelName.get(channel);
        }
        if (core != null) {
            core.onFrame(connection.getPlayer().getUniqueId(),
                    connection.getServerInfo().getName(), event.getData());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        List<ProxyChannelCore> cores;
        synchronized (this) {
            cores = List.copyOf(byNamespace.values());
        }
        for (ProxyChannelCore core : cores) {
            core.closeCarrier(event.getPlayer().getUniqueId());
        }
    }

    private void sweepAll() {
        List<ProxyChannelCore> cores;
        synchronized (this) {
            cores = List.copyOf(byNamespace.values());
        }
        for (ProxyChannelCore core : cores) {
            core.sweep();
        }
    }

    // -------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------

    /** Aggregated status: one block per namespace with its live backends and counters. */
    public synchronized String statusReport() {
        if (!available()) {
            return "SnBridge SIN secreto HMAC en el proxy: nada fluye (ver log de arranque).";
        }
        if (byNamespace.isEmpty()) {
            return "SnBridge: sin namespaces reclamados en el proxy.";
        }
        StringBuilder report = new StringBuilder("SnBridge proxy status:\n");
        List<ProxyChannelCore> sorted = new ArrayList<>(byNamespace.values());
        sorted.sort(Comparator.comparing(ProxyChannelCore::namespace));
        for (ProxyChannelCore core : sorted) {
            report.append("- ").append(core.namespace())
                    .append(" cola=").append(core.pending())
                    .append(' ').append(core.counters().snapshot()).append('\n');
            for (String server : core.liveServers()) {
                ProxyChannelCore.BackendInfo info = core.capabilities(server);
                report.append("    ").append(server)
                        .append(": sesiones=").append(core.readySessionsOn(server));
                if (info != null) {
                    report.append(" msgset=").append(info.msgset())
                            .append(" snlib=").append(info.libVersion());
                }
                report.append('\n');
            }
        }
        return report.toString().stripTrailing();
    }
}
