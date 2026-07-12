package com.sn.lib.velocity.internal;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import com.velocitypowered.api.event.player.ServerConnectedEvent;
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
 * <p>Security model, stated honestly: Velocity only fires {@link PluginMessageEvent}
 * for channels present in its ChannelRegistrar, so this runtime can sink exactly the
 * REGISTERED snlib channels (every claimed namespace, the pre-registered infra channel
 * and the detectLegacy claims); traffic on an UNCLAIMED snlib channel is forwarded by
 * the platform untouched, and the floor that makes it inert on both ends is the HMAC
 * (garbage without the key), not this listener. Registered channel names are announced
 * to clients via minecraft:register - they are not secrets by design. Only messages
 * whose source is a {@link ServerConnection} are ever processed.</p>
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
    private final ProxyChannelCore verbsCore;
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
        // Tier 2 verb channel, wired up front so its traffic ALWAYS lands on this
        // listener; every verb call is correlated, so the dispatcher is a no-op
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from("snlib:bridge"));
        this.verbsCore = createCore("snlib", 1, "snlib:bridge",
                (type, carrier, serverName, message) -> { });
        verbsCore.registerTypes(com.sn.lib.bridge.wire.Verbs.Console.TYPE,
                com.sn.lib.bridge.wire.Verbs.Message.TYPE,
                com.sn.lib.bridge.wire.Verbs.Title.TYPE,
                com.sn.lib.bridge.wire.Verbs.Actionbar.TYPE,
                com.sn.lib.bridge.wire.Verbs.Sound.TYPE,
                com.sn.lib.bridge.wire.Verbs.Bossbar.TYPE,
                com.sn.lib.bridge.wire.Verbs.Actions.TYPE,
                com.sn.lib.bridge.wire.Verbs.Ack.TYPE,
                com.sn.lib.bridge.wire.Verbs.AllowlistReq.TYPE,
                com.sn.lib.bridge.wire.Verbs.Allowlist.TYPE);
        byNamespace.put("snlib", verbsCore);
        byChannelName.put("snlib:bridge", verbsCore);
        proxy.getEventManager().register(bootstrap, this);
    }

    /** The Tier 2 verbs channel core (namespace "snlib" over snlib:bridge). */
    public ProxyChannelCore verbsCore() {
        return verbsCore;
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
                    "SnBridge not initialized on the proxy: SnLib.jar missing from Velocity's plugins/");
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
        ProxyChannelCore core = createCore(namespace, msgset, channelName, dispatcher);
        byNamespace.put(namespace, core);
        byChannelName.put(channelName, core);
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(channelName));
        return core;
    }

    /** Builds one core wired to the channel sink and a rate-limited logger seam. */
    private ProxyChannelCore createCore(String namespace, int msgset, String channelName,
            ProxyChannelCore.Dispatcher dispatcher) {
        return new ProxyChannelCore(namespace, msgset, libVersion, signer,
                msgIds::incrementAndGet, () -> System.nanoTime() / 1_000_000L,
                sinkFor(channelName), dispatcher,
                new ProxyChannelCore.Log() {
                    private volatile long lastWarn;

                    @Override
                    public void warn(String message) {
                        // Rate-limited as the seam contract demands (and the spec for NACKs)
                        long now = System.nanoTime() / 1_000_000L;
                        if (now - lastWarn > 10_000L) {
                            lastWarn = now;
                            logger.warn("[SnBridge] {}", message);
                        }
                    }

                    @Override
                    public void debug(java.util.function.Supplier<String> message) {
                        // Velocity: no SnDebug; the proxy logger's debug level decides
                        if (logger.isDebugEnabled()) {
                            logger.debug("[SnBridge] {}", message.get());
                        }
                    }
                },
                30_000L, 256, 8 * 1024 * 1024, 8);
    }

    /** Registers a legacy channel for outdated-counterpart detection (sunk + counted). */
    public synchronized void registerLegacy(String legacyChannel, ProxyChannelCore core) {
        MinecraftChannelIdentifier identifier;
        try {
            identifier = MinecraftChannelIdentifier.from(legacyChannel);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid legacy channel: '" + legacyChannel
                    + "' (namespace:name format)", e);
        }
        proxy.getChannelRegistrar().register(identifier);
        legacyChannels.put(legacyChannel, core);
    }

    private ProxyChannelCore.CarrierSink sinkFor(String channelName) {
        MinecraftChannelIdentifier identifier = MinecraftChannelIdentifier.from(channelName);
        return (carrier, serverName, frames) -> {
            Optional<Player> player = proxy.getPlayer(carrier);
            if (player.isEmpty()) {
                return false;
            }
            Optional<ServerConnection> connection = player.get().getCurrentServer();
            if (connection.isEmpty()) {
                return false;
            }
            // Defense against the server-switch race: a frame signed for 'gens' is
            // NEVER written to another backend's connection with a positive SENT
            if (!connection.get().getServerInfo().getName()
                    .toLowerCase(Locale.ROOT).equals(serverName)) {
                return false;
            }
            try {
                boolean allSent = true;
                for (byte[] frame : frames) {
                    allSent &= connection.get().sendPluginMessage(identifier, frame);
                }
                return allSent;
            } catch (IllegalStateException e) {
                // Velocity throws 'Not connected to server!' (it never returns false)
                // right in the switch teardown window: to the core that is an honest false
                return false;
            }
        };
    }

    // -------------------------------------------------------
    // Events
    // -------------------------------------------------------

    private final Map<String, Long> legacyWarnAt = new HashMap<>(4);

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();
        ProxyChannelCore legacyOwner;
        synchronized (this) {
            legacyOwner = legacyChannels.get(channel);
        }
        if (legacyOwner != null) {
            // Old-stack channel claimed via detectLegacy: ALWAYS sink it, but only
            // count/warn on real backend traffic (a client cannot spam the log or
            // inflate the diagnostics) and rate-limited like the Paper side
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (event.getSource() instanceof ServerConnection) {
                legacyOwner.counters().legacyFrame();
                long now = System.nanoTime() / 1_000_000L;
                boolean warn;
                synchronized (this) {
                    Long last = legacyWarnAt.get(channel);
                    warn = last == null || now - last > 30_000L;
                    if (warn) {
                        legacyWarnAt.put(channel, now);
                    }
                }
                if (warn) {
                    logger.warn("[SnBridge] Traffic on legacy channel '{}': the backend"
                            + " counterpart of '{}' is still on the old stack (update it)",
                            channel, legacyOwner.namespace());
                }
            }
            return;
        }
        if (!channel.startsWith("snlib:")) {
            return;
        }
        // Every REGISTERED snlib channel lands here and is sunk; unclaimed ones fire
        // no event (platform limit documented in the class javadoc)
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return; // frame originated by a client: spoof, dies here
        }
        ProxyChannelCore core;
        synchronized (this) {
            core = byChannelName.get(channel);
        }
        if (core != null) {
            core.onFrame(connection.getPlayer().getUniqueId(),
                    connection.getServerInfo().getName().toLowerCase(Locale.ROOT),
                    event.getData());
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

    /**
     * Velocity does NOT fire DisconnectEvent on a backend switch: without this, the old
     * backend's session survives the switch and sends to it would ride a carrier now
     * connected elsewhere. Conditional close (stale only) so a fresh HELLO from the new
     * backend that raced ahead of this event is never wiped; kicks also end here via
     * the fallback server's ServerConnectedEvent.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String currentServer = event.getServer().getServerInfo().getName().toLowerCase(Locale.ROOT);
        List<ProxyChannelCore> cores;
        synchronized (this) {
            cores = List.copyOf(byNamespace.values());
        }
        for (ProxyChannelCore core : cores) {
            core.closeCarrierIfNotOn(event.getPlayer().getUniqueId(), currentServer);
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
            return "SnBridge has NO HMAC secret on the proxy: nothing flows (see startup log).";
        }
        if (byNamespace.isEmpty()) {
            return "SnBridge: no namespaces claimed on the proxy.";
        }
        StringBuilder report = new StringBuilder("SnBridge proxy status:\n");
        List<ProxyChannelCore> sorted = new ArrayList<>(byNamespace.values());
        sorted.sort(Comparator.comparing(ProxyChannelCore::namespace));
        for (ProxyChannelCore core : sorted) {
            report.append("- ").append(core.namespace())
                    .append(" queue=").append(core.pending())
                    .append(' ').append(core.counters().snapshot()).append('\n');
            for (String server : core.liveServers()) {
                com.sn.lib.velocity.SnBackendInfo info = core.capabilities(server);
                report.append("    ").append(server)
                        .append(": sessions=").append(core.readySessionsOn(server));
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
