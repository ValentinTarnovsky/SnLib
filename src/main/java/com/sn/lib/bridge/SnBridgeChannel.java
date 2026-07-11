package com.sn.lib.bridge;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.sn.lib.Sn;
import com.sn.lib.SnExperimental;
import com.sn.lib.bridge.internal.BridgeRuntime;
import com.sn.lib.bridge.internal.ChannelCore;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.WireIds;
import com.sn.lib.bridge.wire.SnWireException;
import com.sn.lib.db.SnFuture;

/**
 * Typed bridge channel of one namespace on the Paper side. Register the plugin's wire
 * types once, attach handlers, then send/request; every send resolves with a terminal
 * {@link SnDelivery} (never void, never silent). Handlers run on the main thread with
 * the carrier player already resolved.
 *
 * <p>Sends without a ready handshake queue up (bounded, TTL'd) and flush strictly after
 * HELLO_ACK; watch {@link #onState} for WARMING/READY instead of guessing. Main-thread
 * confined like the whole module.</p>
 */
@SnExperimental
public final class SnBridgeChannel {

    private final Sn ctx;
    private final BridgeRuntime runtime;
    private final ChannelCore core;
    private final String channelName;
    private final HandlerTable handlers;
    private long lastLegacyWarn;

    /** Built by the runtime on {@code sn.bridge().channel(...)}; not for consumers. */
    public SnBridgeChannel(Sn ctx, BridgeRuntime runtime, ChannelCore core, String channelName,
            HandlerTable handlers) {
        this.ctx = ctx;
        this.runtime = runtime;
        this.core = core;
        this.channelName = channelName;
        this.handlers = handlers;
    }

    /** Namespace this channel serves. */
    public String namespace() {
        return core.namespace();
    }

    /** Aggregated WARMING/READY over the live handshaken connections. */
    public SnBridgeState state() {
        return core.state();
    }

    /**
     * Msgset the proxy reported in HELLO_ACK, taken across the CURRENTLY ready sessions;
     * -1 whenever none is ready right now (warming window, last carrier quit). Cache it
     * via {@link #onState} if "was ever negotiated" semantics are needed.
     */
    public int remoteMsgset() {
        return core.remoteMsgset();
    }

    /** Messages waiting in the carrier queue right now. */
    public int pending() {
        return core.pending();
    }

    /**
     * Registers this plugin's wire types (idempotent per type, duplicate ids hard-fail).
     * Consumer types must NOT claim the reserved {@code snlib:} prefix.
     */
    public void register(SnWireType<?>... types) {
        for (SnWireType<?> type : types) {
            if (type.wireId().startsWith(WireIds.RESERVED_PREFIX)) {
                throw new SnWireException("wireId '" + type.wireId()
                        + "' usa el prefijo reservado snlib:");
            }
        }
        core.registry().register(types);
    }

    /** Handler for one message type; runs on the main thread with the carrier player. */
    public <T> void on(SnWireType<T> type, BiConsumer<Player, T> handler) {
        handlers.put(type, handler);
    }

    /** State transitions (WARMING/READY); fired on the main thread. */
    public void onState(Consumer<SnBridgeState> callback) {
        core.onState(callback);
    }

    /** Fire-and-forget riding THIS player's connection, default TTL. */
    public <T> CompletableFuture<SnDelivery> send(Player carrier, SnWireType<T> type, T message) {
        return send(carrier, type, message, SnSendOpts.defaults());
    }

    /** Fire-and-forget riding THIS player's connection. */
    public <T> CompletableFuture<SnDelivery> send(Player carrier, SnWireType<T> type, T message,
            SnSendOpts opts) {
        return core.send(carrier.getUniqueId(), type, message, opts.ttlMillis());
    }

    /** Fire-and-forget over ANY ready carrier (namespace-level messages). */
    public <T> CompletableFuture<SnDelivery> sendAny(SnWireType<T> type, T message, SnSendOpts opts) {
        return core.send(null, type, message, opts.ttlMillis());
    }

    /**
     * Request/response with correlation and timeout, over any ready carrier (queued
     * until handshake otherwise, still bounded by the timeout). Completes exceptionally
     * on NACK or timeout - wire a {@code .exceptionally(...)} or the failure is yours.
     *
     * <p>Responses, timeouts and teardown all resolve ON the main thread: consume via
     * {@code thenSync}/{@code exceptionally}; {@code join()} from the main thread throws
     * instead of deadlocking the server.</p>
     */
    @SuppressWarnings("unchecked")
    public <T, R> SnFuture<R> request(SnWireType<T> requestType, T request,
            SnWireType<R> responseType, Duration timeout) {
        CompletableFuture<Object> raw = core.request(requestType, request, responseType,
                Math.max(1L, timeout.toMillis()));
        return SnFuture.wrapMainCompleted(ctx, raw.thenApply(message -> (R) message));
    }

    /**
     * Migration helper: also listens on the plugin's OLD stack channel and logs (rate
     * limited) when traffic appears there, so a half-migrated pair is noise, not mute.
     * Only the NEW side can detect: the old side is old code.
     */
    public void detectLegacy(String legacyChannel) {
        runtime.registerLegacy(legacyChannel, this);
    }

    // -------------------------------------------------------
    // Internal plumbing (runtime side, not consumer API)
    // -------------------------------------------------------

    /** Owning consumer plugin. */
    public Plugin owner() {
        return ctx.plugin();
    }

    /** Internal core; used by the runtime and the status command. */
    public ChannelCore core() {
        return core;
    }

    /** Bukkit channel string ({@code snlib:ext/<namespace>}). */
    public String channelName() {
        return channelName;
    }

    /** Counts + rate-limited warn when legacy-stack traffic shows up mid-migration. */
    public void onLegacyFrame(String legacyChannel) {
        core.counters().legacyFrame();
        long now = System.currentTimeMillis();
        if (now - lastLegacyWarn > 30_000L) {
            lastLegacyWarn = now;
            ctx.plugin().getLogger().warning("[SnBridge] Trafico en el canal legacy '"
                    + legacyChannel + "': la contraparte proxy de '" + core.namespace()
                    + "' sigue en el stack viejo (actualizarla)");
        }
    }

    /**
     * Handler table + logging seam handed to the core BEFORE the channel wrapper exists
     * (the core needs a dispatcher at construction). Resolves the carrier Player and
     * runs the consumer handler, already on the main thread.
     */
    public static final class HandlerTable implements ChannelCore.Dispatcher, ChannelCore.Log {

        private final Sn ctx;
        private final String namespace;
        private final Map<String, BiConsumer<Player, Object>> byWireId = new HashMap<>(8);
        private long lastWarn;

        public HandlerTable(Sn ctx, String namespace) {
            this.ctx = ctx;
            this.namespace = namespace;
        }

        @SuppressWarnings("unchecked")
        <T> void put(SnWireType<T> type, BiConsumer<Player, T> handler) {
            byWireId.put(type.wireId(), (BiConsumer<Player, Object>) handler);
        }

        @Override
        public void dispatch(SnWireType<?> type, java.util.UUID carrier, Object message) {
            BiConsumer<Player, Object> handler = byWireId.get(type.wireId());
            if (handler == null) {
                ctx.debug().log("bridge", () -> "[" + namespace + "] sin handler para "
                        + type.wireId() + ", mensaje ignorado");
                return;
            }
            Player player = Bukkit.getPlayer(carrier);
            if (player == null) {
                return; // carrier quit between frame arrival and dispatch (same tick edge)
            }
            try {
                handler.accept(player, message);
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning("[SnBridge] handler de " + type.wireId()
                        + " lanzo " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        @Override
        public void warn(String message) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 10_000L) {
                lastWarn = now;
                ctx.plugin().getLogger().warning("[SnBridge] " + message);
            }
        }

        @Override
        public void debug(Supplier<String> message) {
            ctx.debug().log("bridge", message);
        }
    }
}
