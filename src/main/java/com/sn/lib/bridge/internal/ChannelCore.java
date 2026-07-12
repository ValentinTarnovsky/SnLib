package com.sn.lib.bridge.internal;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.sn.lib.bridge.SnBridgeState;
import com.sn.lib.bridge.SnDelivery;
import com.sn.lib.bridge.SnDeliveryResult;
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
import com.sn.lib.bridge.wire.SnNackException;
import com.sn.lib.bridge.wire.SnWireException;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.UnknownWireIdException;
import com.sn.lib.bridge.wire.WireProtocol;
import com.sn.lib.bridge.wire.WireTypeRegistry;

/**
 * Per-namespace bridge logic of the Paper side, DELIBERATELY free of Bukkit imports so
 * the whole state machine is unit-testable: sessions and HELLO handshake, the bounded
 * TTL'd carrier queue, request/response correlation, NACK handling and counters. The
 * Bukkit glue (players, Messenger, main-thread dispatch, logging) lives in the wrapper
 * and reaches this core through the small {@link CarrierSink}/{@link Dispatcher}/
 * {@link Log} interfaces.
 *
 * <p>Main-thread confined: Bukkit delivers plugin messages on the main thread and every
 * public API call happens there too; no synchronization on purpose.</p>
 */
public final class ChannelCore {

    /** Delivers raw frames to a carrier connection; false when the carrier is gone. */
    public interface CarrierSink {
        boolean deliver(UUID carrier, List<byte[]> frames);
    }

    /** Hands a decoded application message to the consumer handler layer. */
    public interface Dispatcher {
        void dispatch(SnWireType<?> type, UUID carrier, Object message);
    }

    /** Logging seam: warn is rate-limited by the caller side, debug is lazy. */
    public interface Log {
        void warn(String message);

        void debug(Supplier<String> message);
    }

    /** Serves one request type arriving FROM the proxy; returns the response message. */
    public interface Responder {
        Object respond(UUID carrier, Object request);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String namespace;
    private final int msgset;
    private final String libVersion;
    private final Map<String, Integer> capabilities;
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
    private final Map<String, RespondEntry> responders = new HashMap<>(4);
    private final Map<UUID, Session> sessions = new HashMap<>(8);
    private final ArrayDeque<QueuedSend> queue = new ArrayDeque<>();
    private final Map<Integer, PendingRequest> requests = new HashMap<>(8);
    private final List<Consumer<SnBridgeState>> stateCallbacks = new ArrayList<>(2);
    private final BridgeCounters counters = new BridgeCounters();

    /** Set FIRST by teardown; a closed core resolves every call immediately, never queues. */
    private boolean closed;

    public ChannelCore(String namespace, int msgset, String libVersion,
            Map<String, Integer> capabilities, HmacSigner signer, IntSupplier msgIds,
            LongSupplier clock, CarrierSink sink, Dispatcher dispatcher, Log log,
            long defaultTtlMillis, int queueCap, int maxMessageBytes, int maxPendingPerConnection) {
        this.namespace = namespace;
        this.msgset = msgset;
        this.libVersion = libVersion;
        this.capabilities = Map.copyOf(capabilities);
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

    public int msgset() {
        return msgset;
    }

    public WireTypeRegistry registry() {
        return registry;
    }

    public BridgeCounters counters() {
        return counters;
    }

    public SnBridgeState state() {
        for (Session session : sessions.values()) {
            if (session.ready) {
                return SnBridgeState.READY;
            }
        }
        return SnBridgeState.WARMING;
    }

    /**
     * Best msgset the proxy reported among the CURRENTLY ready sessions, or -1 when
     * none is ready right now (warming window, or last carrier quit). Deliberately not
     * sticky: a stale value would survive a proxy redeploy across an empty server.
     */
    public int remoteMsgset() {
        int best = -1;
        for (Session session : sessions.values()) {
            if (session.ready && session.remoteMsgset > best) {
                best = session.remoteMsgset;
            }
        }
        return best;
    }

    public int pending() {
        return queue.size();
    }

    public int sessionCount() {
        return sessions.size();
    }

    public int readySessionCount() {
        int ready = 0;
        for (Session session : sessions.values()) {
            if (session.ready) {
                ready++;
            }
        }
        return ready;
    }

    public void onState(Consumer<SnBridgeState> callback) {
        stateCallbacks.add(callback);
    }

    /**
     * Registers a respond handler: when {@code requestWireId} arrives, the responder's
     * return value travels back auto-correlated (same msgId + response flag) so the
     * proxy's request future completes. A handler throw answers a typed INTERNAL_ERROR
     * NACK instead of silence.
     */
    public void respond(String requestWireId, SnWireType<?> responseType, Responder responder) {
        RespondEntry previous = responders.putIfAbsent(requestWireId,
                new RespondEntry(responseType, responder));
        if (previous != null) {
            throw new SnWireException("Duplicate respond handler for '" + requestWireId + "'");
        }
    }

    // -------------------------------------------------------
    // Sessions and handshake
    // -------------------------------------------------------

    /**
     * Opens the session of one carrier and fires HELLO. A READY session is NEVER
     * clobbered (duplicate join/register signals must not desync a live nonce); a
     * non-ready one is replaced fresh. The HELLO may not leave yet (the connection may
     * not have REGISTERed the channel: the sink returns false); {@link #sweep} retries
     * with the SAME session nonce until it leaves or the attempt cap trips.
     */
    public void openSession(UUID carrier) {
        if (signer == null || closed) {
            return; // without an HMAC secret the bridge is off; a severe was logged at startup
        }
        Session existing = sessions.get(carrier);
        if (existing != null && existing.ready) {
            return;
        }
        Session session = new Session(RANDOM.nextLong(), maxMessageBytes, maxPendingPerConnection);
        sessions.put(carrier, session);
        tryHello(carrier, session);
    }

    /**
     * Sends (or re-sends) the HELLO of a non-ready session, reusing its backendNonce so
     * an ACK already in flight can never pair with a nonce we abandoned.
     */
    private void tryHello(UUID carrier, Session session) {
        session.helloAttempts++;
        HelloMsg hello = new HelloMsg(WireProtocol.FRAME_VERSION_MIN, WireProtocol.FRAME_VERSION,
                msgset, libVersion, session.backendNonce, capabilities);
        if (deliverFrames(carrier, HelloMsg.TYPE.encodeMessage(hello), msgIds.getAsInt(),
                WireProtocol.HANDSHAKE_NONCE)) {
            session.helloDelivered = true;
            counters.helloSent++;
            log.debug(() -> "[" + namespace + "] HELLO sent via " + carrier);
        } else {
            log.debug(() -> "[" + namespace + "] HELLO not deliverable yet via " + carrier
                    + " (channel not registered), retrying on the next sweep");
        }
    }

    /** Drops one carrier's session (quit/switch); partial reassembly dies with it. */
    public void closeSession(UUID carrier) {
        Session removed = sessions.remove(carrier);
        if (removed == null) {
            return;
        }
        if (removed.ready && readySessionCount() == 0) {
            fireState(SnBridgeState.WARMING);
        }
    }

    // -------------------------------------------------------
    // Outbound
    // -------------------------------------------------------

    /**
     * Typed fire-and-forget. {@code target} null means "any ready carrier". The future
     * ALWAYS resolves: SENT now, or later via queue flush, or EXPIRED_TTL.
     */
    public <T> CompletableFuture<SnDelivery> send(UUID target, SnWireType<T> type, T message,
            long ttlMillis) {
        CompletableFuture<SnDelivery> future = new CompletableFuture<>();
        if (closed) {
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL, "channel released (shutdown)"));
            return future;
        }
        if (signer == null) {
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "bridge has no HMAC secret configured"));
            return future;
        }
        byte[] body = type.encodeMessage(message);
        UUID carrier = pickReadyCarrier(target);
        if (carrier != null) {
            completeSend(carrier, body, type.wireId(), future);
            return future;
        }
        enqueue(new QueuedSend(target, type.wireId(), body, expiry(ttlMillis), future, null));
        return future;
    }

    /**
     * Request/response with correlation by msgId. Rides any ready carrier (or queues).
     * The future completes with the decoded response, or exceptionally on NACK/timeout.
     */
    public <T, R> CompletableFuture<Object> request(SnWireType<T> requestType, T request,
            SnWireType<R> responseType, long timeoutMillis) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new SnWireException("channel released (shutdown)"));
            return future;
        }
        if (signer == null) {
            future.completeExceptionally(new SnWireException("bridge has no HMAC secret configured"));
            return future;
        }
        byte[] body = requestType.encodeMessage(request);
        long deadline = clock.getAsLong() + timeoutMillis;
        UUID carrier = pickReadyCarrier(null);
        if (carrier != null) {
            int msgId = msgIds.getAsInt();
            requests.put(msgId, new PendingRequest(responseType.wireId(), future, deadline));
            Session session = sessions.get(carrier);
            if (deliverFrames(carrier, body, msgId, session.sessionNonce)) {
                counters.sent++;
            } else {
                requests.remove(msgId);
                counters.expired++;
                future.completeExceptionally(new SnWireException(
                        "carrier disconnected while sending the request"));
            }
            return future;
        }
        CompletableFuture<SnDelivery> sendFuture = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(responseType.wireId(), future, deadline);
        enqueue(new QueuedSend(null, requestType.wireId(), body, deadline, sendFuture, pendingRequest));
        sendFuture.thenAccept(delivery -> {
            if (!delivery.ok() && !future.isDone()) {
                future.completeExceptionally(new SnWireException(
                        "request expired in queue: " + delivery.detail()));
            }
        });
        return future;
    }

    // -------------------------------------------------------
    // Inbound
    // -------------------------------------------------------

    /** Feeds one raw inbound frame (proxy to backend direction) from a carrier. */
    public void onFrame(UUID carrier, byte[] frame) {
        if (signer == null) {
            return;
        }
        Session session = sessions.get(carrier);
        if (session == null) {
            counters.orphanFrames++;
            log.debug(() -> "[" + namespace + "] frame without session from " + carrier + ", dropped");
            return;
        }
        long nonce = session.ready ? session.sessionNonce : WireProtocol.HANDSHAKE_NONCE;
        FrameHeader header;
        try {
            header = FrameCodec.decode(frame, signer, nonce, false);
        } catch (SnWireException e) {
            counters.hmacDrops++;
            log.debug(() -> "[" + namespace + "] frame rejected: " + e.getMessage());
            return;
        }
        byte[] body;
        try {
            body = session.reassembler.accept(header, FrameCodec.body(frame));
        } catch (SnWireException e) {
            counters.malformed++;
            log.warn("[" + namespace + "] invalid chunking: " + e.getMessage());
            return;
        }
        if (body == null) {
            return; // partial, waiting for more chunks
        }
        counters.received++;
        WireTypeRegistry.DecodedMessage decoded;
        try {
            decoded = registry.decode(body);
        } catch (UnknownWireIdException e) {
            counters.malformed++;
            sendNack(carrier, session, header.msgId(), e.wireId(), NackReason.UNKNOWN_WIRE_ID,
                    "type not registered on this backend");
            return;
        } catch (SnWireException e) {
            counters.malformed++;
            sendNack(carrier, session, header.msgId(), "", NackReason.MALFORMED, e.getMessage());
            return;
        }
        route(carrier, session, header.msgId(), header.isResponse(), decoded);
    }

    // -------------------------------------------------------
    // Housekeeping
    // -------------------------------------------------------

    /**
     * Periodic sweep: expires queued sends and timed-out requests, and retries the
     * HELLO of sessions whose first attempt could not leave yet (channel unREGISTERed).
     *
     * <p>Drain-then-complete discipline (here and in flushQueue/teardown): consumer
     * futures complete strictly AFTER every iterator over our collections is dead, so a
     * callback that re-enters send()/request() can never blow up the iteration.</p>
     */
    public void sweep() {
        long now = clock.getAsLong();
        List<QueuedSend> expiredSends = null;
        for (Iterator<QueuedSend> it = queue.iterator(); it.hasNext();) {
            QueuedSend entry = it.next();
            if (now >= entry.expiresAt) {
                it.remove();
                counters.expired++;
                if (expiredSends == null) {
                    expiredSends = new ArrayList<>(4);
                }
                expiredSends.add(entry);
            }
        }
        List<PendingRequest> timedOut = null;
        for (Iterator<Map.Entry<Integer, PendingRequest>> it = requests.entrySet().iterator(); it.hasNext();) {
            PendingRequest pending = it.next().getValue();
            if (now >= pending.deadline) {
                it.remove();
                if (timedOut == null) {
                    timedOut = new ArrayList<>(4);
                }
                timedOut.add(pending);
            }
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Session session = entry.getValue();
            if (session.ready || session.helloDelivered) {
                continue;
            }
            if (session.helloAttempts < MAX_HELLO_ATTEMPTS) {
                tryHello(entry.getKey(), session);
            } else if (session.helloAttempts == MAX_HELLO_ATTEMPTS) {
                session.helloAttempts++;
                log.warn("[" + namespace + "] channel snlib:ext/" + namespace
                        + " was never registered by the connection of " + entry.getKey()
                        + ": proxy counterpart missing or outdated");
            }
        }
        if (expiredSends != null) {
            for (QueuedSend entry : expiredSends) {
                entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                        "expired in queue without carrier/handshake (" + entry.wireId + ")"));
            }
        }
        if (timedOut != null) {
            for (PendingRequest pending : timedOut) {
                pending.future.completeExceptionally(new SnWireException(
                        "request got no response from the proxy (timeout)"));
            }
        }
    }

    /** Terminal teardown: resolves everything in flight, drops every session. */
    public void teardown() {
        closed = true; // FIRST: reentrant calls from completions resolve, never repopulate
        List<QueuedSend> drainedQueue = List.copyOf(queue);
        queue.clear();
        List<PendingRequest> drainedRequests = List.copyOf(requests.values());
        requests.clear();
        sessions.clear();
        stateCallbacks.clear();
        for (QueuedSend entry : drainedQueue) {
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL, "channel shutdown"));
        }
        for (PendingRequest pending : drainedRequests) {
            pending.future.completeExceptionally(new SnWireException("channel shutdown"));
        }
    }

    // -------------------------------------------------------
    // Internals
    // -------------------------------------------------------

    private void route(UUID carrier, Session session, int msgId, boolean isResponse,
            WireTypeRegistry.DecodedMessage decoded) {
        Object message = decoded.message();
        if (message instanceof HelloAckMsg ack) {
            onHelloAck(carrier, session, ack);
            return;
        }
        if (message instanceof NackMsg nack) {
            counters.nacksReceived++;
            PendingRequest pending = requests.remove(nack.refMsgId());
            if (pending != null) {
                pending.future.completeExceptionally(new SnNackException(nack.reason(),
                        "NACK from the proxy (" + nack.reason() + "): " + nack.detail()));
            }
            log.warn("[" + namespace + "] NACK " + nack.reason() + " for '" + nack.refWireId()
                    + "': " + nack.detail());
            return;
        }
        if (message instanceof HeartbeatMsg heartbeat) {
            // Echo ONLY a fresh ping: re-echoing a response-flagged heartbeat that has no
            // pending match would loop forever between backend and proxy
            if (!isResponse) {
                deliverFrames(carrier, HeartbeatMsg.TYPE.encodeMessage(heartbeat), msgId,
                        session.sessionNonce, true);
                return;
            }
            PendingRequest pending = requests.get(msgId);
            if (pending != null && pending.responseWireId.equals(HeartbeatMsg.TYPE.wireId())) {
                requests.remove(msgId);
                pending.future.complete(heartbeat);
            }
            return; // a response with no matching pending is dropped, never re-echoed
        }
        // Correlation REQUIRES the response flag: a push whose msgId happens to collide
        // with an in-flight request id must dispatch normally, never be swallowed
        if (isResponse) {
            PendingRequest pending = requests.get(msgId);
            if (pending != null && pending.responseWireId.equals(decoded.type().wireId())) {
                requests.remove(msgId);
                pending.future.complete(message);
            }
            return; // a response with no matching pending is dropped, never dispatched
        }
        RespondEntry responderEntry = responders.get(decoded.type().wireId());
        if (responderEntry != null) {
            Object response;
            try {
                response = responderEntry.responder.respond(carrier, message);
            } catch (Throwable t) {
                log.warn("[" + namespace + "] responder for " + decoded.type().wireId()
                        + " threw " + t);
                sendNack(carrier, session, msgId, decoded.type().wireId(),
                        NackReason.INTERNAL_ERROR, String.valueOf(t.getMessage()));
                return;
            }
            // The reply travels under the SAME msgId + response flag: proxy-side correlation
            if (deliverFrames(carrier, encodeResponse(responderEntry.responseType, response),
                    msgId, session.sessionNonce, true)) {
                counters.sent++;
            }
            return;
        }
        dispatcher.dispatch(decoded.type(), carrier, message);
    }

    @SuppressWarnings("unchecked")
    private static <R> byte[] encodeResponse(SnWireType<R> type, Object response) {
        return type.encodeMessage((R) response);
    }

    private void onHelloAck(UUID carrier, Session session, HelloAckMsg ack) {
        if (session.ready) {
            log.debug(() -> "[" + namespace + "] duplicate HELLO_ACK from " + carrier + ", ignored");
            return;
        }
        if (ack.frameVersion() < WireProtocol.FRAME_VERSION_MIN
                || ack.frameVersion() > WireProtocol.FRAME_VERSION) {
            counters.malformed++;
            log.warn("[" + namespace + "] HELLO_ACK with frameVersion " + ack.frameVersion()
                    + " out of range; session discarded (update SnLib on one side)");
            sessions.remove(carrier);
            return;
        }
        session.sessionNonce = session.backendNonce ^ ack.nonce();
        session.remoteMsgset = ack.msgsetVersion();
        session.ready = true;
        counters.handshakes++;
        log.debug(() -> "[" + namespace + "] handshake ready via " + carrier + " (proxy msgset "
                + ack.msgsetVersion() + ", SnLib " + ack.libVersion() + ")");
        if (readySessionCount() == 1) {
            fireState(SnBridgeState.READY);
        }
        flushQueue(carrier);
    }

    private void flushQueue(UUID readyCarrier) {
        List<Completion> completions = null;
        for (Iterator<QueuedSend> it = queue.iterator(); it.hasNext();) {
            QueuedSend entry = it.next();
            UUID carrier = entry.target == null ? readyCarrier : entry.target;
            Session session = sessions.get(carrier);
            if (session == null || !session.ready) {
                continue;
            }
            it.remove();
            SnDelivery delivery;
            if (entry.request != null) {
                int msgId = msgIds.getAsInt();
                requests.put(msgId, entry.request);
                if (deliverFrames(carrier, entry.body, msgId, session.sessionNonce)) {
                    counters.sent++;
                    delivery = SnDelivery.sent();
                } else {
                    requests.remove(msgId);
                    counters.expired++;
                    delivery = SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                            "carrier disconnected during the flush");
                }
            } else if (deliverFrames(carrier, entry.body, msgIds.getAsInt(), session.sessionNonce)) {
                counters.sent++;
                delivery = SnDelivery.sent();
            } else {
                counters.expired++;
                delivery = SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                        "carrier disconnected while sending (" + entry.wireId + ")");
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

    private void completeSend(UUID carrier, byte[] body, String wireId,
            CompletableFuture<SnDelivery> future) {
        Session session = sessions.get(carrier);
        if (deliverFrames(carrier, body, msgIds.getAsInt(), session.sessionNonce)) {
            counters.sent++;
            future.complete(SnDelivery.sent());
        } else {
            counters.expired++;
            future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "carrier disconnected while sending (" + wireId + ")"));
        }
    }

    private boolean deliverFrames(UUID carrier, byte[] body, int msgId, long nonce) {
        return deliverFrames(carrier, body, msgId, nonce, false);
    }

    private boolean deliverFrames(UUID carrier, byte[] body, int msgId, long nonce,
            boolean response) {
        List<byte[]> frames = Chunker.split(body, true, response, msgId, signer, nonce);
        return sink.deliver(carrier, frames);
    }

    private void sendNack(UUID carrier, Session session, int refMsgId, String refWireId,
            NackReason reason, String detail) {
        if (!session.ready) {
            return; // without a session nonce there is no secure channel to reply on
        }
        NackMsg nack = new NackMsg(refMsgId, refWireId, reason, detail == null ? "" : detail);
        if (deliverFrames(carrier, NackMsg.TYPE.encodeMessage(nack), msgIds.getAsInt(),
                session.sessionNonce)) {
            counters.nacksSent++;
        }
    }

    private UUID pickReadyCarrier(UUID target) {
        if (target != null) {
            Session session = sessions.get(target);
            return session != null && session.ready ? target : null;
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().ready) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void enqueue(QueuedSend entry) {
        if (closed) {
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "channel released (shutdown)"));
            return;
        }
        if (entry.expiresAt <= clock.getAsLong()) {
            counters.expired++;
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "zero TTL and no ready carrier (" + entry.wireId + ")"));
            return;
        }
        if (queue.size() >= queueCap) {
            counters.queueOverflow++;
            entry.future.complete(SnDelivery.of(SnDeliveryResult.EXPIRED_TTL,
                    "queue full (" + queueCap + ") for '" + entry.wireId + "'"));
            return;
        }
        queue.addLast(entry);
    }

    private long expiry(long ttlMillis) {
        long ttl = ttlMillis < 0 ? defaultTtlMillis : ttlMillis;
        return clock.getAsLong() + ttl;
    }

    private void fireState(SnBridgeState state) {
        // Copy: a callback may register another onState mid-iteration
        for (Consumer<SnBridgeState> callback : List.copyOf(stateCallbacks)) {
            try {
                callback.accept(state);
            } catch (Throwable t) {
                log.warn("[" + namespace + "] state callback threw " + t);
            }
        }
    }

    /** HELLO retries before the one-shot warn about a never-registered channel. */
    private static final int MAX_HELLO_ATTEMPTS = 5;

    private static final class Session {
        final long backendNonce;
        final ChunkReassembler reassembler;
        long sessionNonce;
        int remoteMsgset = -1;
        boolean ready;
        boolean helloDelivered;
        int helloAttempts;

        Session(long backendNonce, int maxMessageBytes, int maxPendingPerConnection) {
            this.backendNonce = backendNonce;
            this.reassembler = new ChunkReassembler(maxMessageBytes, maxPendingPerConnection);
        }
    }

    private record QueuedSend(UUID target, String wireId, byte[] body, long expiresAt,
            CompletableFuture<SnDelivery> future, PendingRequest request) {
    }

    private record Completion(CompletableFuture<SnDelivery> future, SnDelivery delivery) {
    }

    private record PendingRequest(String responseWireId, CompletableFuture<Object> future,
            long deadline) {
    }

    private record RespondEntry(SnWireType<?> responseType, Responder responder) {
    }
}
