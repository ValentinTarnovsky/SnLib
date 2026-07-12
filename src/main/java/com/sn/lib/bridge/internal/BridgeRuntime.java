package com.sn.lib.bridge.internal;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLibPlugin;
import com.sn.lib.bridge.SnBridgeChannel;
import com.sn.lib.bridge.wire.HmacSigner;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Server-wide bridge runtime, owned by the SnLib bootstrap exactly like the bStats
 * metrics: ONE instance per server regardless of how many consumers claim namespaces.
 * Holds the HMAC signer (resolved once at enable), the shared msgId counter, the
 * namespace-to-channel maps, the Bukkit Messenger registrations (always under the SnLib
 * plugin, mirroring the ListenerHub philosophy) and the periodic queue/timeout sweep.
 *
 * <p>Namespace claims are first-claim-wins and tracked in a {@link TenantRegistry}, so
 * a consumer that disables without cleanup is swept by the standard tenant mechanism;
 * {@code Sn.shutdown} step 12 also releases explicitly for deterministic ordering.</p>
 */
public final class BridgeRuntime {

    private static volatile @Nullable BridgeRuntime instance;

    private final SnLibPlugin plugin;
    private final @Nullable HmacSigner signer;
    private final long defaultTtlMillis;
    private final int queueCap;
    private final int maxMessageBytes;
    private final int maxPendingPerConnection;
    private final AtomicInteger msgIds = new AtomicInteger(new SecureRandom().nextInt());
    private final Map<String, SnBridgeChannel> channels = new HashMap<>(8);
    private final Map<String, SnBridgeChannel> byChannelName = new HashMap<>(8);
    private final Map<String, SnBridgeChannel> legacyChannels = new HashMap<>(4);
    private final TaskHandle sweeper;

    /**
     * Server-wide static justified: TenantRegistry instances are library-lifetime by
     * contract (the static REGISTRIES set has no removal path); a per-runtime instance
     * would leak one registry per enable/disable cycle.
     */
    private static final TenantRegistry<SnBridgeChannel> CLAIMS =
            new TenantRegistry<>(BridgeRuntime::sweepRelease);

    private BridgeRuntime(SnLibPlugin plugin, Sn selfCtx) {
        this.plugin = plugin;
        byte[] secret = SecretResolver.resolve(selfCtx.yml().config(), plugin.getLogger());
        this.signer = secret == null ? null : new HmacSigner(secret);
        this.defaultTtlMillis = 1000L
                * Math.max(1, selfCtx.yml().config().getInt("bridge.default-ttl-seconds", 30));
        this.queueCap = Math.max(1, selfCtx.yml().config().getInt("bridge.queue-cap", 256));
        this.maxMessageBytes = Math.max(64 * 1024,
                selfCtx.yml().config().getInt("bridge.max-message-bytes", 8 * 1024 * 1024));
        this.maxPendingPerConnection = Math.max(1,
                selfCtx.yml().config().getInt("bridge.max-pending-messages", 8));
        this.sweeper = selfCtx.scheduler().timer(20L, 20L, this::sweepAll);
    }

    /** Called once from SnLibPlugin.onEnable, after the self context exists. */
    public static void init(SnLibPlugin plugin, Sn selfCtx) {
        BridgeRuntime runtime = new BridgeRuntime(plugin, selfCtx);
        instance = runtime;
        if (runtime.available()) {
            // Tier 2: SnLib itself serves the generic verbs on snlib:bridge, so a
            // proxy-only plugin needs no Paper jar of its own
            SnBridgeChannel verbChannel = runtime.createChannel(selfCtx, "snlib", 1,
                    "snlib:bridge", VerbService.CAPABILITIES);
            VerbService.install(selfCtx, verbChannel.core());
        }
    }

    /** Called from SnLibPlugin.onDisable after the consumer cascade. */
    public static void shutdownRuntime() {
        BridgeRuntime runtime = instance;
        if (runtime == null) {
            return;
        }
        instance = null;
        runtime.sweeper.cancel();
        for (SnBridgeChannel channel : List.copyOf(runtime.channels.values())) {
            CLAIMS.remove(channel.owner(), channel);
            runtime.release(channel);
        }
    }

    /** Explicit release from Sn.shutdown (step 12); the tenant sweep in step 13 is the net. */
    public static void releaseOwner(Plugin owner) {
        BridgeRuntime runtime = instance;
        if (runtime == null) {
            return;
        }
        for (SnBridgeChannel channel : List.copyOf(CLAIMS.forOwner(owner))) {
            CLAIMS.remove(owner, channel);
            runtime.release(channel);
        }
    }

    /** Live runtime or null (status command and listeners tolerate a disabled bridge). */
    public static @Nullable BridgeRuntime live() {
        return instance;
    }

    /** False when no HMAC secret could be resolved: channels register but nothing flows. */
    public boolean available() {
        return signer != null;
    }

    // -------------------------------------------------------
    // Claims
    // -------------------------------------------------------

    /**
     * Claims a namespace for a consumer context: hard error when another owner holds it,
     * idempotent for the same owner (returns the live channel).
     */
    public static SnBridgeChannel claim(Sn ctx, String namespace, int msgset) {
        BridgeRuntime runtime = instance;
        if (runtime == null) {
            throw new IllegalStateException("SnBridge not initialized: SnLib is not enabled");
        }
        return runtime.doClaim(ctx, namespace, msgset);
    }

    private SnBridgeChannel doClaim(Sn ctx, String namespace, int msgset) {
        validateNamespace(namespace);
        SnBridgeChannel existing = channels.get(namespace);
        if (existing != null) {
            if (existing.owner() != ctx.plugin()) {
                throw new IllegalStateException("Bridge namespace '" + namespace
                        + "' already claimed by " + existing.owner().getName()
                        + " (first-claim-wins, pick another namespace)");
            }
            return existing;
        }
        return createChannel(ctx, namespace, msgset, "snlib:ext/" + namespace, Map.of());
    }

    private SnBridgeChannel createChannel(Sn ctx, String namespace, int msgset,
            String channelName, Map<String, Integer> capabilities) {
        SnBridgeChannel.HandlerTable handlers = new SnBridgeChannel.HandlerTable(ctx, namespace);
        ChannelCore core = new ChannelCore(namespace, msgset,
                plugin.getPluginMeta().getVersion(), capabilities, signer,
                msgIds::incrementAndGet, () -> System.nanoTime() / 1_000_000L,
                sinkFor(channelName), handlers, handlers,
                defaultTtlMillis, queueCap, maxMessageBytes, maxPendingPerConnection);
        SnBridgeChannel channel = new SnBridgeChannel(ctx, this, core, channelName, handlers);
        channels.put(namespace, channel);
        byChannelName.put(channelName, channel);
        CLAIMS.add(ctx.plugin(), channel);
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, channelName);
        messenger.registerIncomingPluginChannel(plugin, channelName, BridgeMessageListener.INSTANCE);
        for (Player online : Bukkit.getOnlinePlayers()) {
            core.openSession(online.getUniqueId());
        }
        return channel;
    }

    private ChannelCore.CarrierSink sinkFor(String channelName) {
        return (carrier, frames) -> {
            Player player = Bukkit.getPlayer(carrier);
            if (player == null || !player.isOnline()) {
                return false;
            }
            // Honest sink: before the connection REGISTERs the channel (minecraft:register,
            // which arrives AFTER PlayerJoinEvent behind a proxy), sendPluginMessage no-ops
            // silently; report false so HELLOs retry and sends queue instead of fake SENT
            if (!player.getListeningPluginChannels().contains(channelName)) {
                return false;
            }
            for (byte[] frame : frames) {
                player.sendPluginMessage(plugin, channelName, frame);
            }
            return true;
        };
    }

    private void release(SnBridgeChannel channel) {
        channels.remove(channel.core().namespace());
        byChannelName.remove(channel.channelName());
        Messenger messenger = Bukkit.getMessenger();
        for (var it = legacyChannels.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, SnBridgeChannel> entry = it.next();
            if (entry.getValue() == channel) {
                messenger.unregisterIncomingPluginChannel(plugin, entry.getKey(),
                        BridgeMessageListener.INSTANCE);
                it.remove();
            }
        }
        messenger.unregisterOutgoingPluginChannel(plugin, channel.channelName());
        messenger.unregisterIncomingPluginChannel(plugin, channel.channelName());
        channel.core().teardown();
    }

    /** TenantRegistry sweep callback shape (static: the instance may already be gone). */
    private static void sweepRelease(SnBridgeChannel channel) {
        BridgeRuntime runtime = instance;
        if (runtime != null) {
            runtime.release(channel);
        }
    }

    /** Registers a legacy channel to detect an outdated counterpart (migration window). */
    public void registerLegacy(String legacyChannel, SnBridgeChannel channel) {
        Messenger messenger = Bukkit.getMessenger();
        // Bukkit call BEFORE the map put, idempotent (double detectLegacy in one enable)
        if (!messenger.isIncomingChannelRegistered(plugin, legacyChannel)) {
            messenger.registerIncomingPluginChannel(plugin, legacyChannel,
                    BridgeMessageListener.INSTANCE);
        }
        legacyChannels.put(legacyChannel, channel);
    }

    // -------------------------------------------------------
    // Event plumbing (shared listeners)
    // -------------------------------------------------------

    void onPluginMessage(String channelName, Player player, byte[] data) {
        SnBridgeChannel channel = byChannelName.get(channelName);
        if (channel != null) {
            channel.core().onFrame(player.getUniqueId(), data);
            return;
        }
        SnBridgeChannel legacyOwner = legacyChannels.get(channelName);
        if (legacyOwner != null) {
            legacyOwner.onLegacyFrame(channelName);
        }
    }

    void onJoin(Player player) {
        // Fast path for connections that already REGISTERed (non-proxied or reload);
        // behind Velocity the register arrives later and onChannelRegistered covers it
        for (SnBridgeChannel channel : List.copyOf(channels.values())) {
            channel.core().openSession(player.getUniqueId());
        }
    }

    /**
     * The real HELLO trigger behind a proxy: fires when the connection announces the
     * channel via minecraft:register, the earliest moment sendPluginMessage stops
     * no-oping. openSession never clobbers a READY session, so duplicate registers are
     * harmless.
     */
    void onChannelRegistered(Player player, String channelName) {
        SnBridgeChannel channel = byChannelName.get(channelName);
        if (channel != null) {
            channel.core().openSession(player.getUniqueId());
        }
    }

    void onQuit(Player player) {
        for (SnBridgeChannel channel : List.copyOf(channels.values())) {
            channel.core().closeSession(player.getUniqueId());
        }
    }

    private void sweepAll() {
        // Copy: a consumer completion/state callback may claim or release mid-sweep
        for (SnBridgeChannel channel : List.copyOf(channels.values())) {
            channel.core().sweep();
        }
    }

    // -------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------

    /** Status lines for {@code /snlib bridge status}, one per claimed namespace. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>(channels.size() + 1);
        if (!available()) {
            lines.add("&cBridge WITHOUT HMAC secret: nothing flows (see the SnLib startup log).");
        }
        if (channels.isEmpty()) {
            lines.add("&7No bridge namespaces claimed.");
            return lines;
        }
        List<SnBridgeChannel> sorted = new ArrayList<>(channels.values());
        sorted.sort(Comparator.comparing(channel -> channel.core().namespace()));
        for (SnBridgeChannel channel : sorted) {
            ChannelCore core = channel.core();
            lines.add("&8- &f" + core.namespace()
                    + " &7(" + channel.owner().getName() + ") &f"
                    + core.state().name().toLowerCase(Locale.ROOT)
                    + " &7sessions=" + core.readySessionCount() + "/" + core.sessionCount()
                    + " msgset=" + core.msgset()
                    + (core.remoteMsgset() >= 0 ? "->" + core.remoteMsgset() : "")
                    + " queue=" + core.pending());
            lines.add("&8    " + core.counters().snapshot());
        }
        return lines;
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank() || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid bridge namespace: '" + namespace
                    + "' (lowercase [a-z0-9_-], no ':' or '/')");
        }
        if (namespace.startsWith("snlib")) {
            throw new IllegalArgumentException(
                    "Namespace '" + namespace + "' is reserved for SnLib");
        }
    }
}
