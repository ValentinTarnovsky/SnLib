package com.sn.lib.velocity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.SnExperimental;
import com.sn.lib.bridge.SnDelivery;
import com.sn.lib.bridge.SnDeliveryResult;
import com.sn.lib.bridge.SnSendOpts;
import com.sn.lib.bridge.wire.SnWireException;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.WireIds;
import com.sn.lib.velocity.internal.ProxyBridgeRuntime;
import com.sn.lib.velocity.internal.ProxyChannelCore;
import com.velocitypowered.api.proxy.Player;

/**
 * Typed bridge channel of one namespace on the PROXY side: register the plugin's wire
 * types, attach {@code on}/{@code respond} handlers, and send toward backends by server
 * name. Every send resolves with a terminal {@link SnDelivery}; a backend with no live
 * session queues (bounded, TTL'd) and expires visibly, and a name that is not a
 * registered server resolves {@link SnDeliveryResult#UNKNOWN_SERVER} immediately.
 *
 * <p>Threading (Velocity): handlers and future completions run on the netty/scheduler
 * thread that produced them, like every Velocity event. No main thread exists here.</p>
 */
@SnExperimental
public final class SnProxyChannel {

    private final ProxyBridgeRuntime runtime;
    private final ProxyChannelCore core;
    private final Map<String, BiConsumer<SnProxySource, Object>> handlers;

    SnProxyChannel(ProxyBridgeRuntime runtime, ProxyChannelCore core,
            Map<String, BiConsumer<SnProxySource, Object>> handlers) {
        this.runtime = runtime;
        this.core = core;
        this.handlers = handlers;
    }

    /** Namespace this channel serves. */
    public String namespace() {
        return core.namespace();
    }

    /**
     * Registers this plugin's wire types (shared with the backend via the plugin's
     * common module). Consumer types must not claim the reserved {@code snlib:} prefix.
     */
    public void register(SnWireType<?>... types) {
        for (SnWireType<?> type : types) {
            if (type.wireId().startsWith(WireIds.RESERVED_PREFIX)) {
                throw new SnWireException("wireId '" + type.wireId()
                        + "' uses the reserved snlib: prefix");
            }
        }
        core.registerTypes(types); // under the core monitor: netty threads read it
    }

    /** Handler for one message type arriving from any backend. */
    @SuppressWarnings("unchecked")
    public <T> void on(SnWireType<T> type, BiConsumer<SnProxySource, T> handler) {
        handlers.put(type.wireId(), (BiConsumer<SnProxySource, Object>) handler);
    }

    /**
     * Serves a request type: the handler's return value travels back auto-correlated
     * (same msgId) so the backend's {@code request(...)} future completes. A handler
     * throw answers a typed INTERNAL_ERROR NACK instead of silence.
     */
    @SuppressWarnings("unchecked")
    public <T, R> void respond(SnWireType<T> requestType, SnWireType<R> responseType,
            BiFunction<SnProxySource, T, R> handler) {
        core.respond(requestType.wireId(), responseType, (carrier, serverName, request) -> {
            SnProxySource source = resolveSource(carrier, serverName);
            return handler.apply(source, (T) request);
        });
    }

    /** Send targets one backend server by its registered name. */
    public Destination to(String serverName) {
        return new Destination(serverName);
    }

    /** Negotiation data of one backend, or empty while it has no live session. */
    public Optional<ProxyChannelCore.BackendInfo> capabilities(String serverName) {
        return Optional.ofNullable(core.capabilities(normalize(serverName)));
    }

    /** Server names are matched lowercased (Velocity resolves them case-insensitively). */
    private static String normalize(String serverName) {
        return serverName.toLowerCase(java.util.Locale.ROOT);
    }

    /** Messages queued right now waiting for a backend session. */
    public int pending() {
        return core.pending();
    }

    /**
     * Migration helper: sinks and counts traffic on the plugin's OLD stack channel and
     * warns that the backend counterpart is outdated. Only the NEW side can detect.
     */
    public void detectLegacy(String legacyChannel) {
        runtime.registerLegacy(legacyChannel, core);
    }

    private SnProxySource resolveSource(java.util.UUID carrier, String serverName) {
        Player player = runtime.proxy().getPlayer(carrier).orElse(null);
        return new SnProxySource(player, serverName);
    }

    /** One-backend send surface. */
    @SnExperimental
    public final class Destination {

        private final String serverName;

        private Destination(String serverName) {
            this.serverName = serverName;
        }

        public <T> CompletableFuture<SnDelivery> send(SnWireType<T> type, T message) {
            return send(type, message, SnSendOpts.defaults());
        }

        public <T> CompletableFuture<SnDelivery> send(SnWireType<T> type, T message,
                SnSendOpts opts) {
            if (runtime.proxy().getServer(serverName).isEmpty()) {
                return CompletableFuture.completedFuture(SnDelivery.of(
                        SnDeliveryResult.UNKNOWN_SERVER,
                        "'" + serverName + "' is not a server registered in velocity.toml"));
            }
            // Normalized: getServer is case-insensitive but the core matches exactly
            return core.sendToServer(normalize(serverName), type, message, opts.ttlMillis());
        }
    }

    /** Builds the dispatcher handed to the core BEFORE this wrapper exists. */
    static ProxyChannelCore.Dispatcher dispatcher(ProxyBridgeRuntime runtime,
            Map<String, BiConsumer<SnProxySource, Object>> handlers, String namespace) {
        return (type, carrier, serverName, message) -> {
            BiConsumer<SnProxySource, Object> handler = handlers.get(type.wireId());
            if (handler == null) {
                return; // no handler: application message ignored on purpose
            }
            Player player = runtime.proxy().getPlayer(carrier).orElse(null);
            if (player == null) {
                return; // the carrier disconnected in flight: the SnProxySource contract
                        // guarantees a non-null player, so the handler is skipped
            }
            try {
                handler.accept(new SnProxySource(player, serverName), message);
            } catch (Throwable t) {
                runtime.logger().warn("[SnBridge] handler of {} in '{}' threw {}",
                        type.wireId(), namespace, t.toString());
            }
        };
    }

    /**
     * Used by SnProxy to build the wrapper set. Concurrent on purpose: on() writes from
     * the consumer's init thread while netty threads read during dispatch.
     */
    static Map<String, BiConsumer<SnProxySource, Object>> newHandlerTable() {
        return new java.util.concurrent.ConcurrentHashMap<>(8);
    }
}
