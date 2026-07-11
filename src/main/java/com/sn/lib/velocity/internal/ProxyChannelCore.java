package com.sn.lib.velocity.internal;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.bridge.SnDelivery;
import com.sn.lib.bridge.SnDeliveryResult;
import com.sn.lib.bridge.internal.BridgeCounters;
import com.sn.lib.bridge.wire.ChunkReassembler;
import com.sn.lib.bridge.wire.Chunker;
import com.sn.lib.bridge.wire.FrameCodec;
import com.sn.lib.bridge.wire.FrameHeader;
import com.sn.lib.bridge.wire.HeartbeatMsg;
import com.sn.lib.bridge.wire.HelloAckMsg;
import com.sn.lib.bridge.wire.HelloMsg;
import com.sn.lib.bridge.wire.HmacSigner;
import com.sn.lib.bridge.wire.NackMsg;
import com.sn.lib.bridge.wire.NackReason;
import com.sn.lib.bridge.wire.SnWireException;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.UnknownWireIdException;
import com.sn.lib.bridge.wire.WireProtocol;
import com.sn.lib.bridge.wire.WireTypeRegistry;

/**
 * Per-namespace bridge logic of the PROXY side, free of Velocity imports so the state
 * machine is unit-testable. Mirrors the backend's ChannelCore with inverted roles: the
 * proxy ANSWERS HELLO (never initiates), routes outbound sends by backend server name,
 * and serves {@code respond()} handlers with msgId-correlated replies.
 *
 * <p>Threading: unlike Paper, Velocity delivers plugin messages on per-connection netty
 * threads, so every public method is {@code synchronized} (coarse on purpose: bridge
 * message rates are join/click-scale, not tick-scale). Consumer handlers and future
 * completions run INSIDE the calling thread but always AFTER internal iteration ends
 * (drain-then-complete), and the lock is reentrant for handlers that send back.</p>
 */
public final class ProxyChannelCore {

    /** Delivers frames over the backend connection of one carrier player. */
    public interface CarrierSink {
        boolean deliver(UUID carrier, List<byte[]> frames);
    }

    /** Hands one decoded application message to the consumer handler layer. */
    public interface Dispatcher {
        void dispatch(SnWireType<?> type, UUID carrier, String serverName, Object message);
    }

    /** Logging seam. */
    public interface Log {
        void warn(String message);

        void debug(Supplier<String> message);
    }

    /** Serves a registered request type; returns the response message (never null). */
    public interface Responder {
        Object respond(UUID carrier, String serverName, Object request);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String namespace;
    private final int msgset;
    private final String libVersion;
    private final HmacSigner signer;
    private final IntSupplier msgIds;
    private final LongSupplier clock;
    private final CarrierSink sink;
    private final Dispatcher dispatcher;
    private final Log log;
    private final long defaultTtlMillis;
    private final int queueCap;
    private final int maxMessageBytes;
    private final int maxPendingPerConnection;

    private final WireTypeRegistry registry = new WireTypeRegistry();
    private final Map<String, RespondEntry> responders = new HashMap<>(8);
    private final Map<UUID, Session> sessions = new HashMap<>(32);
    private final Map<UUID, ChunkReassembler> handshakeReassemblers = new HashMap<>(8);
    private final ArrayDeque<QueuedSend> queue = new ArrayDeque<>();
    private final BridgeCounters counters = new BridgeCounters();
    private boolean closed;

    public ProxyChannelCore(String namespace, int msgset, String libVersion, HmacSigner signer,
            IntSupplier msgIds, LongSupplier clock, CarrierSink sink, Dispatcher dispatcher,
            Log log, long defaultTtlMillis, int queueCap, int maxMessageBytes,
            int maxPendingPerConnection) {
        this.namespace = namespace;
        this.msgset = msgset;
        this.libVersion = libVersion;
        this.signer = signer;
        this.msgIds = msgIds;
        this.clock = clock;
        this.sink = sink;
        this.dispatcher = dispatcher;
        this.log = log;
        this.defaultTtlMillis = defaultTtlMillis;
        this.queueCap = queueCap;
        this.maxMessageBytes = maxMessageBytes;
        this.maxPendingPerConnection = maxPendingPerConnection;
        registry.register(HelloMsg.TYPE, HelloAckMsg.TYPE, NackMsg.TYPE, HeartbeatMsg.TYPE);
    }

    public String namespace() {
        return namespace;
    }

    public WireTypeRegistry registry() {
        return registry;
    }

    public BridgeCounters counters() {
        return counters;
    }

    /** Registers a respond handler: request wireId -> (handler, response type). */
    public synchronized void respond(String requestWireId, SnWireType<?> responseType,
            Responder responder) {
        RespondEntry previous = responders.putIfAbsent(requestWireId,
                new RespondEntry(responseType, responder));
        if (previous != null) {
            throw new SnWireException("respond duplicado para '" + requestWireId + "'");
        }
    }

    /** Ready sessions currently living on the given backend server. */
    public synchronized int readySessionsOn(String serverName) {
        int count = 0;
        for (Session session : sessions.values()) {
            if (session.serverName.equals(serverName)) {
                count++;
            }
        }
        return count;
    }

    public synchronized int pending() {
        return queue.size();
    }

    /** Negotiation data of the given backend, or null while no session lives there. */
    public synchronized @Nullable BackendInfo capabilities(String serverName) {
        for (Session session : sessions.values()) {
            if (session.serverName.equals(serverName)) {
                return new BackendInfo(session.remoteMsgset, session.remoteLibVersion,
                        session.remoteCapabilities);
            }
        }
        return null;
    }

    /** Distinct backend servers with at least one live session (for the status report). */
    public synchronized List<String> liveServers() {
        List<String> servers = new ArrayList<>(4);
        for (Session session : sessions.values()) {
            if (!servers.contains(session.serverName)) {
                servers.add(session.serverName);
            }
        }
        return servers;
    }

    // -------------------------------------------------------
    // Outbound (proxy -> backend, routed by server name)
    // -------------------------------------------------------

    /** Typed fire-and-forget toward one backend server; queues (TTL) while it has no session. */
    public synchronized <T> CompletableFuture<SnDelivery> sendToServer(String serverName,
            SnWireType<T> type, T message, long ttlMillis) {
        CompletableFuture<SnDelivery> future = new CompletableFuture<>();
        if (closed) {
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL, "canal liberado (shutdown)"));
            return future;
        }
        if (signer == null) {
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "bridge sin secreto HMAC configurado"));
            return future;
        }
        byte[] body = type.encodeMessage(message);
        UUID carrier = pickCarrierOn(serverName);
        if (carrier != null) {
            deliverAndComplete(carrier, body, type.wireId(), future);
            return future;
        }
        long ttl = ttlMillis < 0 ? defaultTtlMillis : ttlMillis;
        enqueue(new QueuedSend(serverName, type.wireId(), body, clock.getAsLong() + ttl, future));
        return future;
    }

    // -------------------------------------------------------
    // Inbound (backend -> proxy)
    // -------------------------------------------------------

    /**
     * Feeds one raw frame that arrived from a backend over {@code carrier}'s connection.
     * Order: session nonce first, handshake nonce as fallback (a new HELLO after a
     * server switch or backend restart arrives while an old session still exists).
     */
    public synchronized void onFrame(UUID carrier, String serverName, byte[] frame) {
        if (signer == null || closed) {
            return;
        }
        Session session = sessions.get(carrier);
        if (session != null && session.serverName.equals(serverName)) {
            FrameHeader header = tryDecode(frame, session.sessionNonce);
            if (header != null) {
                byte[] body = reassemble(session.reassembler, header, frame);
                if (body != null) {
                    counters.received++;
                    routeApp(carrier, session, header.msgId(), body);
                }
                return;
            }
        }
        FrameHeader header = tryDecode(frame, WireProtocol.HANDSHAKE_NONCE);
        if (header == null) {
            counters.hmacDrops++;
            log.debug(() -> "[" + namespace + "] frame rechazado de " + serverName
                    + " via " + carrier);
            return;
        }
        ChunkReassembler reassembler = handshakeReassemblers.computeIfAbsent(carrier,
                key -> new ChunkReassembler(maxMessageBytes, maxPendingPerConnection));
        byte[] body = reassemble(reassembler, header, frame);
        if (body == null) {
            return;
        }
        handshakeReassemblers.remove(carrier);
        onHandshakeBody(carrier, serverName, body);
    }

    /** Drops every session and handshake state riding this carrier (disconnect/switch). */
    public synchronized void closeCarrier(UUID carrier) {
        sessions.remove(carrier);
        handshakeReassemblers.remove(carrier);
    }

    /** Periodic sweep: expires queued sends (drain-then-complete). */
    public synchronized void sweep() {
        long now = clock.getAsLong();
        List<QueuedSend> expired = null;
        for (Iterator<QueuedSend> it = queue.iterator(); it.hasNext();) {
            QueuedSend entry = it.next();
            if (now >= entry.expiresAt) {
                it.remove();
                counters.expired++;
                if (expired == null) {
                    expired = new ArrayList<>(4);
                }
                expired.add(entry);
            }
        }
        if (expired != null) {
            for (QueuedSend entry : expired) {
                entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                        "expiro en cola: sin sesion en '" + entry.serverName + "' ("
                                + entry.wireId + ")"));
            }
        }
    }

    /** Terminal teardown. */
    public synchronized void teardown() {
        closed = true;
        List<QueuedSend> drained = List.copyOf(queue);
        queue.clear();
        sessions.clear();
        handshakeReassemblers.clear();
        for (QueuedSend entry : drained) {
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL, "shutdown del canal"));
        }
    }

    // -------------------------------------------------------
    // Internals
    // -------------------------------------------------------

    private @Nullable FrameHeader tryDecode(byte[] frame, long nonce) {
        try {
            return FrameCodec.decode(frame, signer, nonce, true);
        } catch (SnWireException e) {
            return null;
        }
    }

    private byte @Nullable [] reassemble(ChunkReassembler reassembler, FrameHeader header,
            byte[] frame) {
        try {
            return reassembler.accept(header, FrameCodec.body(frame));
        } catch (SnWireException e) {
            counters.malformed++;
            log.warn("[" + namespace + "] chunking invalido: " + e.getMessage());
            return null;
        }
    }

    private void onHandshakeBody(UUID carrier, String serverName, byte[] body) {
        Object message;
        try {
            message = registry.decode(body).message();
        } catch (SnWireException e) {
            counters.malformed++;
            return;
        }
        if (!(message instanceof HelloMsg hello)) {
            counters.malformed++;
            return;
        }
        int negotiated = Math.min(WireProtocol.FRAME_VERSION, hello.frameVersionMax());
        int floor = Math.max(WireProtocol.FRAME_VERSION_MIN, hello.frameVersionMin());
        if (negotiated < floor) {
            counters.malformed++;
            log.warn("[" + namespace + "] HELLO de '" + serverName + "' con frames ["
                    + hello.frameVersionMin() + "," + hello.frameVersionMax()
                    + "] sin interseccion con [" + WireProtocol.FRAME_VERSION_MIN + ","
                    + WireProtocol.FRAME_VERSION + "]: actualizar SnLib de un lado");
            return;
        }
        long proxyNonce = RANDOM.nextLong();
        HelloAckMsg ack = new HelloAckMsg(negotiated, msgset, libVersion, proxyNonce, Map.of());
        if (!deliverFrames(carrier, HelloAckMsg.TYPE.encodeMessage(ack), msgIds.getAsInt(),
                WireProtocol.HANDSHAKE_NONCE)) {
            log.debug(() -> "[" + namespace + "] HELLO_ACK no entregable via " + carrier);
            return;
        }
        Session session = new Session(serverName, hello.nonce() ^ proxyNonce,
                hello.msgsetVersion(), hello.libVersion(), hello.capabilities(),
                new ChunkReassembler(maxMessageBytes, maxPendingPerConnection));
        sessions.put(carrier, session);
        counters.handshakes++;
        log.debug(() -> "[" + namespace + "] handshake con '" + serverName + "' via " + carrier
                + " (backend msgset " + hello.msgsetVersion() + ", SnLib " + hello.libVersion() + ")");
        if (hello.msgsetVersion() != msgset) {
            log.warn("[" + namespace + "] msgset local " + msgset + " vs backend '" + serverName
                    + "' " + hello.msgsetVersion() + ": flota mixta, campos aditivos cubren");
        }
        flushQueue(serverName, carrier);
    }

    private void routeApp(UUID carrier, Session session, int msgId, byte[] body) {
        WireTypeRegistry.DecodedMessage decoded;
        try {
            decoded = registry.decode(body);
        } catch (UnknownWireIdException e) {
            counters.malformed++;
            sendNack(carrier, session, msgId, e.wireId(), NackReason.UNKNOWN_WIRE_ID,
                    "tipo no registrado en el proxy");
            return;
        } catch (SnWireException e) {
            counters.malformed++;
            sendNack(carrier, session, msgId, "", NackReason.MALFORMED, e.getMessage());
            return;
        }
        Object message = decoded.message();
        if (message instanceof HelloMsg) {
            counters.malformed++;
            return; // un HELLO firmado con nonce de sesion no existe en el protocolo
        }
        if (message instanceof NackMsg nack) {
            counters.nacksReceived++;
            log.warn("[" + namespace + "] NACK " + nack.reason() + " de '" + session.serverName
                    + "' para '" + nack.refWireId() + "': " + nack.detail());
            return;
        }
        if (message instanceof HeartbeatMsg heartbeat) {
            deliverFrames(carrier, HeartbeatMsg.TYPE.encodeMessage(heartbeat), msgId,
                    session.sessionNonce);
            return;
        }
        RespondEntry responderEntry = responders.get(decoded.type().wireId());
        if (responderEntry != null) {
            Object response;
            try {
                response = responderEntry.responder.respond(carrier, session.serverName, message);
            } catch (Throwable t) {
                counters.nacksSent++;
                sendNack(carrier, session, msgId, decoded.type().wireId(),
                        NackReason.INTERNAL_ERROR, String.valueOf(t.getMessage()));
                log.warn("[" + namespace + "] responder de " + decoded.type().wireId()
                        + " lanzo " + t);
                return;
            }
            byte[] responseBody = encodeResponse(responderEntry.responseType, response);
            // La respuesta viaja con el MISMO msgId: la correlacion del backend depende de eso
            if (deliverFrames(carrier, responseBody, msgId, session.sessionNonce)) {
                counters.sent++;
            }
            return;
        }
        dispatcher.dispatch(decoded.type(), carrier, session.serverName, message);
    }

    @SuppressWarnings("unchecked")
    private static <R> byte[] encodeResponse(SnWireType<R> type, Object response) {
        return type.encodeMessage((R) response);
    }

    private void flushQueue(String serverName, UUID carrier) {
        List<Completion> completions = null;
        for (Iterator<QueuedSend> it = queue.iterator(); it.hasNext();) {
            QueuedSend entry = it.next();
            if (!entry.serverName.equals(serverName)) {
                continue;
            }
            it.remove();
            SnDelivery delivery;
            if (deliverFrames(carrier, entry.body, msgIds.getAsInt(),
                    sessions.get(carrier).sessionNonce)) {
                counters.sent++;
                delivery = SnDelivery.sent();
            } else {
                counters.expired++;
                delivery = SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                        "carrier desconectado durante el flush");
            }
            if (completions == null) {
                completions = new ArrayList<>(4);
            }
            completions.add(new Completion(entry.future, delivery));
        }
        if (completions != null) {
            for (Completion completion : completions) {
                completion.future.complete(completion.delivery);
            }
        }
    }

    private void deliverAndComplete(UUID carrier, byte[] body, String wireId,
            CompletableFuture<SnDelivery> future) {
        Session session = sessions.get(carrier);
        if (deliverFrames(carrier, body, msgIds.getAsInt(), session.sessionNonce)) {
            counters.sent++;
            future.complete(SnDelivery.sent());
        } else {
            counters.expired++;
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "conexion al backend perdida durante el envio (" + wireId + ")"));
        }
    }

    private boolean deliverFrames(UUID carrier, byte[] body, int msgId, long nonce) {
        List<byte[]> frames = Chunker.split(body, false, msgId, signer, nonce);
        return sink.deliver(carrier, frames);
    }

    private void sendNack(UUID carrier, Session session, int refMsgId, String refWireId,
            NackReason reason, String detail) {
        NackMsg nack = new NackMsg(refMsgId, refWireId, reason, detail == null ? "" : detail);
        if (deliverFrames(carrier, NackMsg.TYPE.encodeMessage(nack), msgIds.getAsInt(),
                session.sessionNonce)) {
            counters.nacksSent++;
        }
    }

    private @Nullable UUID pickCarrierOn(String serverName) {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().serverName.equals(serverName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void enqueue(QueuedSend entry) {
        if (entry.expiresAt <= clock.getAsLong()) {
            counters.expired++;
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "TTL cero y sin sesion en '" + entry.serverName + "'"));
            return;
        }
        if (queue.size() >= queueCap) {
            counters.queueOverflow++;
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "cola llena (" + queueCap + ") para '" + entry.wireId + "'"));
            return;
        }
        queue.addLast(entry);
    }

    /** Negotiation snapshot of one backend. */
    public record BackendInfo(int msgset, String libVersion, Map<String, Integer> capabilities) {
    }

    private record RespondEntry(SnWireType<?> responseType, Responder responder) {
    }

    private record QueuedSend(String serverName, String wireId, byte[] body, long expiresAt,
            CompletableFuture<SnDelivery> future) {
    }

    private record Completion(CompletableFuture<SnDelivery> future, SnDelivery delivery) {
    }

    private static final class Session {
        final String serverName;
        final long sessionNonce;
        final int remoteMsgset;
        final String remoteLibVersion;
        final Map<String, Integer> remoteCapabilities;
        final ChunkReassembler reassembler;

        Session(String serverName, long sessionNonce, int remoteMsgset, String remoteLibVersion,
                Map<String, Integer> remoteCapabilities, ChunkReassembler reassembler) {
            this.serverName = serverName;
            this.sessionNonce = sessionNonce;
            this.remoteMsgset = remoteMsgset;
            this.remoteLibVersion = remoteLibVersion;
            this.remoteCapabilities = remoteCapabilities;
            this.reassembler = reassembler;
        }
    }
}
