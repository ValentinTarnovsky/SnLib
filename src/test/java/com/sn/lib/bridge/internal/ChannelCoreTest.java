package com.sn.lib.bridge.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.WireProtocol;
import com.sn.lib.bridge.wire.WireTypeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the whole Paper-side state machine against a simulated proxy built from the
 * same wire primitives: handshake, queue flush ordering, TTL/caps, request correlation,
 * NACKs, heartbeat echo and state transitions - all without Bukkit.
 */
class ChannelCoreTest {

    private record TestMsg(UUID player, String data) {
        static final SnWireType<TestMsg> TYPE = SnWireType.of("test:msg", 1,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.data());
                },
                (buf, v) -> new TestMsg(buf.uuid(), buf.str()));
    }

    private record ReqMsg(String query) {
        static final SnWireType<ReqMsg> TYPE = SnWireType.of("test:req", 1,
                (buf, m) -> buf.str(m.query()),
                (buf, v) -> new ReqMsg(buf.str()));
    }

    private record RespMsg(String answer) {
        static final SnWireType<RespMsg> TYPE = SnWireType.of("test:resp", 1,
                (buf, m) -> buf.str(m.answer()),
                (buf, v) -> new RespMsg(buf.str()));
    }

    private static final UUID CARRIER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CARRIER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final HmacSigner signer = new HmacSigner("test-secret".getBytes(StandardCharsets.UTF_8));
    private final AtomicInteger msgIds = new AtomicInteger(100);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Delivered> deliveries = new ArrayList<>();
    private final List<Dispatched> dispatched = new ArrayList<>();
    private final Map<UUID, Long> proxyNonces = new HashMap<>();
    private ChannelCore core;

    private record Delivered(UUID carrier, List<byte[]> frames) {
    }

    private record Dispatched(String wireId, UUID carrier, Object message) {
    }

    @BeforeEach
    void setUp() {
        core = new ChannelCore("test", 3, "1.2.0", Map.of(), signer,
                msgIds::incrementAndGet, clock::get,
                (carrier, frames) -> {
                    deliveries.add(new Delivered(carrier, frames));
                    return true;
                },
                (type, carrier, message) -> dispatched.add(
                        new Dispatched(type.wireId(), carrier, message)),
                new ChannelCore.Log() {
                    @Override
                    public void warn(String message) {
                    }

                    @Override
                    public void debug(java.util.function.Supplier<String> message) {
                    }
                },
                5_000L, 4, 1024 * 1024, 8);
        core.registry().register(TestMsg.TYPE, ReqMsg.TYPE, RespMsg.TYPE);
    }

    // ----- proxy simulation helpers -----

    /** Decodes everything delivered to one carrier since {@code fromIndex}, as the proxy would. */
    private List<ProxyView> proxyDecode(UUID carrier, int fromIndex) {
        WireTypeRegistry proxyRegistry = new WireTypeRegistry();
        proxyRegistry.register(HelloMsg.TYPE, HelloAckMsg.TYPE, NackMsg.TYPE, HeartbeatMsg.TYPE,
                TestMsg.TYPE, ReqMsg.TYPE, RespMsg.TYPE);
        List<ProxyView> out = new ArrayList<>();
        ChunkReassembler reassembler = new ChunkReassembler(1024 * 1024, 8);
        for (int i = fromIndex; i < deliveries.size(); i++) {
            Delivered delivered = deliveries.get(i);
            if (!delivered.carrier.equals(carrier)) {
                continue;
            }
            for (byte[] frame : delivered.frames) {
                long nonce = proxyNonces.getOrDefault(carrier, WireProtocol.HANDSHAKE_NONCE);
                FrameHeader header;
                try {
                    header = FrameCodec.decode(frame, signer, nonce, true);
                } catch (Exception e) {
                    header = FrameCodec.decode(frame, signer, WireProtocol.HANDSHAKE_NONCE, true);
                }
                byte[] body = reassembler.accept(header, FrameCodec.body(frame));
                if (body != null) {
                    out.add(new ProxyView(header.msgId(), proxyRegistry.decode(body).message()));
                }
            }
        }
        return out;
    }

    private record ProxyView(int msgId, Object message) {
    }

    /** Completes the handshake of one carrier and remembers its session nonce. */
    private void handshake(UUID carrier) {
        int before = deliveries.size();
        core.openSession(carrier);
        List<ProxyView> seen = proxyDecode(carrier, before);
        HelloMsg hello = (HelloMsg) seen.get(seen.size() - 1).message();
        long proxyNonce = 0x5A5A_5A5AL ^ carrier.getLeastSignificantBits();
        long sessionNonce = hello.nonce() ^ proxyNonce;
        feed(carrier, HelloAckMsg.TYPE.encodeMessage(
                new HelloAckMsg(1, 5, "proxy-1.2.0", proxyNonce, Map.of())),
                9_000, WireProtocol.HANDSHAKE_NONCE);
        proxyNonces.put(carrier, sessionNonce);
    }

    /** Feeds one proxy-to-backend message into the core. */
    private void feed(UUID carrier, byte[] body, int msgId, long nonce) {
        for (byte[] frame : Chunker.split(body, false, msgId, signer, nonce)) {
            core.onFrame(carrier, frame);
        }
    }

    // ----- tests -----

    @Test
    void handshakeReachesReadyAndReportsRemoteMsgset() {
        List<SnBridgeState> states = new ArrayList<>();
        core.onState(states::add);
        assertEquals(SnBridgeState.WARMING, core.state());
        handshake(CARRIER_A);
        assertEquals(SnBridgeState.READY, core.state());
        assertEquals(5, core.remoteMsgset());
        assertEquals(List.of(SnBridgeState.READY), states);
    }

    @Test
    void sendBeforeHandshakeQueuesAndFlushesAfterAck() {
        CompletableFuture<SnDelivery> future = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "hola"), -1L);
        assertFalse(future.isDone());
        assertEquals(1, core.pending());

        handshake(CARRIER_A);
        assertTrue(future.isDone());
        assertEquals(SnDeliveryResult.SENT, future.join().result());
        assertEquals(0, core.pending());

        List<ProxyView> seen = proxyDecode(CARRIER_A, 0);
        Object last = seen.get(seen.size() - 1).message();
        assertEquals(new TestMsg(CARRIER_A, "hola"), last);
    }

    @Test
    void sendToReadyCarrierResolvesSentImmediately() {
        handshake(CARRIER_A);
        CompletableFuture<SnDelivery> future = core.send(CARRIER_A,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "ya"), -1L);
        assertEquals(SnDeliveryResult.SENT, future.join().result());
    }

    @Test
    void targetedSendWaitsForItsOwnCarrier() {
        handshake(CARRIER_A);
        CompletableFuture<SnDelivery> future = core.send(CARRIER_B,
                TestMsg.TYPE, new TestMsg(CARRIER_B, "para-b"), -1L);
        assertFalse(future.isDone(), "no debe viajar por la sesion de A");
        handshake(CARRIER_B);
        assertEquals(SnDeliveryResult.SENT, future.join().result());
    }

    @Test
    void queuedSendExpiresByTtl() {
        CompletableFuture<SnDelivery> future = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "x"), 2_000L);
        clock.addAndGet(2_001L);
        core.sweep();
        assertEquals(SnDeliveryResult.EXPIRED_TTL, future.join().result());
        assertTrue(core.counters().expired() > 0);
    }

    @Test
    void queueOverflowResolvesImmediately() {
        for (int i = 0; i < 4; i++) {
            core.send(null, TestMsg.TYPE, new TestMsg(CARRIER_A, "q" + i), -1L);
        }
        CompletableFuture<SnDelivery> overflow = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "demasiado"), -1L);
        assertEquals(SnDeliveryResult.EXPIRED_TTL, overflow.join().result());
        assertTrue(overflow.join().detail().contains("cola llena"));
    }

    @Test
    void requestCorrelatesResponseByMsgIdAndSkipsHandler() {
        handshake(CARRIER_A);
        int before = deliveries.size();
        CompletableFuture<Object> future = core.request(ReqMsg.TYPE, new ReqMsg("config?"),
                RespMsg.TYPE, 5_000L);
        List<ProxyView> seen = proxyDecode(CARRIER_A, before);
        int requestMsgId = seen.get(seen.size() - 1).msgId();

        feed(CARRIER_A, RespMsg.TYPE.encodeMessage(new RespMsg("aca-tenes")),
                requestMsgId, proxyNonces.get(CARRIER_A));
        assertEquals(new RespMsg("aca-tenes"), future.join());
        assertTrue(dispatched.isEmpty(), "la respuesta correlacionada no pasa por el handler");
    }

    @Test
    void requestTimesOutThroughSweep() {
        handshake(CARRIER_A);
        CompletableFuture<Object> future = core.request(ReqMsg.TYPE, new ReqMsg("nadie?"),
                RespMsg.TYPE, 1_000L);
        clock.addAndGet(1_001L);
        core.sweep();
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void nackResolvesRequestExceptionally() {
        handshake(CARRIER_A);
        int before = deliveries.size();
        CompletableFuture<Object> future = core.request(ReqMsg.TYPE, new ReqMsg("permitido?"),
                RespMsg.TYPE, 5_000L);
        int requestMsgId = proxyDecode(CARRIER_A, before).get(0).msgId();

        feed(CARRIER_A, NackMsg.TYPE.encodeMessage(new NackMsg(requestMsgId, "test:req",
                NackReason.DENIED_BY_ALLOWLIST, "patron no coincide")),
                8_000, proxyNonces.get(CARRIER_A));
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void wrongNonceFrameIsDroppedAndCounted() {
        handshake(CARRIER_A);
        // Signed with the handshake nonce AFTER the session went ready: replay/reflection food
        feed(CARRIER_A, TestMsg.TYPE.encodeMessage(new TestMsg(CARRIER_A, "spoof")),
                7_000, WireProtocol.HANDSHAKE_NONCE);
        assertTrue(core.counters().hmacDrops() > 0);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void appMessageReachesDispatcher() {
        handshake(CARRIER_A);
        feed(CARRIER_A, TestMsg.TYPE.encodeMessage(new TestMsg(CARRIER_A, "abrir-gui")),
                7_100, proxyNonces.get(CARRIER_A));
        assertEquals(1, dispatched.size());
        assertEquals("test:msg", dispatched.get(0).wireId());
        assertEquals(CARRIER_A, dispatched.get(0).carrier());
    }

    @Test
    void proxyHeartbeatIsEchoedWithSameMsgId() {
        handshake(CARRIER_A);
        int before = deliveries.size();
        feed(CARRIER_A, HeartbeatMsg.TYPE.encodeMessage(new HeartbeatMsg(4242L)),
                7_200, proxyNonces.get(CARRIER_A));
        List<ProxyView> seen = proxyDecode(CARRIER_A, before);
        assertEquals(1, seen.size());
        assertEquals(7_200, seen.get(0).msgId());
        assertEquals(new HeartbeatMsg(4242L), seen.get(0).message());
    }

    @Test
    void unknownWireIdGetsTypedNack() {
        handshake(CARRIER_A);
        SnWireType<RespMsg> alien = SnWireType.of("alien:type", 1,
                (buf, m) -> buf.str(m.answer()),
                (buf, v) -> new RespMsg(buf.str()));
        int before = deliveries.size();
        feed(CARRIER_A, alien.encodeMessage(new RespMsg("?")), 7_300, proxyNonces.get(CARRIER_A));
        List<ProxyView> seen = proxyDecode(CARRIER_A, before);
        assertEquals(1, seen.size());
        NackMsg nack = (NackMsg) seen.get(0).message();
        assertEquals(NackReason.UNKNOWN_WIRE_ID, nack.reason());
        assertEquals("alien:type", nack.refWireId());
        assertEquals(7_300, nack.refMsgId());
    }

    @Test
    void lastCarrierQuitFallsBackToWarming() {
        List<SnBridgeState> states = new ArrayList<>();
        core.onState(states::add);
        handshake(CARRIER_A);
        core.closeSession(CARRIER_A);
        assertEquals(SnBridgeState.WARMING, core.state());
        assertEquals(List.of(SnBridgeState.READY, SnBridgeState.WARMING), states);
    }

    @Test
    void teardownResolvesEverythingInFlight() {
        CompletableFuture<SnDelivery> queued = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "x"), -1L);
        handshake(CARRIER_A);
        CompletableFuture<Object> request = core.request(ReqMsg.TYPE, new ReqMsg("y"),
                RespMsg.TYPE, 60_000L);
        core.teardown();
        assertTrue(queued.isDone());
        assertTrue(request.isCompletedExceptionally());
        assertEquals(0, core.sessionCount());
    }

    @Test
    void reentrantRetryDuringSweepDoesNotBlowTheIteration() {
        // Two timed-out requests whose failure handlers RETRY: the retry mutates the
        // requests map while sweep would still be iterating without drain-then-complete
        handshake(CARRIER_A);
        for (int i = 0; i < 2; i++) {
            core.request(ReqMsg.TYPE, new ReqMsg("r" + i), RespMsg.TYPE, 1_000L)
                    .whenComplete((value, error) -> {
                        if (error != null) {
                            core.request(ReqMsg.TYPE, new ReqMsg("retry"), RespMsg.TYPE, 9_000L);
                        }
                    });
        }
        clock.addAndGet(1_001L);
        core.sweep(); // must not throw ConcurrentModificationException
        // A queued-send callback that re-sends during sweep expiry, same discipline
        CompletableFuture<SnDelivery> expiring = core.send(CARRIER_B,
                TestMsg.TYPE, new TestMsg(CARRIER_B, "x"), 500L);
        expiring.thenAccept(d -> core.send(CARRIER_B, TestMsg.TYPE,
                new TestMsg(CARRIER_B, "reintento"), 500L));
        clock.addAndGet(501L);
        core.sweep();
        assertEquals(SnDeliveryResult.EXPIRED_TTL, expiring.join().result());
    }

    @Test
    void reentrantSendDuringTeardownResolvesInsteadOfHanging() {
        CompletableFuture<SnDelivery> queued = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "x"), -1L);
        List<CompletableFuture<SnDelivery>> reentrant = new ArrayList<>();
        queued.thenAccept(d -> reentrant.add(
                core.send(null, TestMsg.TYPE, new TestMsg(CARRIER_A, "post-shutdown"), -1L)));
        core.teardown();
        assertEquals(1, reentrant.size());
        assertEquals(SnDeliveryResult.EXPIRED_TTL, reentrant.get(0).join().result());
        assertTrue(reentrant.get(0).join().detail().contains("shutdown"),
                "el send reentrante durante teardown resuelve, jamas cuelga");
    }

    @Test
    void stateCallbackRegisteringAnotherCallbackIsSafe() {
        List<SnBridgeState> late = new ArrayList<>();
        core.onState(state -> core.onState(late::add));
        handshake(CARRIER_A); // fires READY while a callback mutates the callback list
        core.closeSession(CARRIER_A); // WARMING reaches the late-registered callback too
        assertEquals(List.of(SnBridgeState.WARMING), late);
    }

    @Test
    void helloRetriesWhenChannelNotYetRegistered() {
        // Simulates the Velocity register race: the sink refuses delivery until the
        // connection REGISTERs the channel; HELLO must retry from sweep with the SAME
        // nonce and the handshake must still complete afterwards
        boolean[] channelRegistered = {false};
        List<Delivered> lateDeliveries = new ArrayList<>();
        ChannelCore racy = new ChannelCore("racy", 1, "1.2.0", Map.of(), signer,
                msgIds::incrementAndGet, clock::get,
                (carrier, frames) -> {
                    if (!channelRegistered[0]) {
                        return false;
                    }
                    lateDeliveries.add(new Delivered(carrier, frames));
                    return true;
                },
                (type, carrier, message) -> { },
                new ChannelCore.Log() {
                    @Override
                    public void warn(String message) {
                    }

                    @Override
                    public void debug(java.util.function.Supplier<String> message) {
                    }
                },
                5_000L, 4, 1024 * 1024, 8);

        racy.openSession(CARRIER_A);
        assertEquals(0, racy.counters().sent());
        assertTrue(lateDeliveries.isEmpty(), "nada salio antes del register");

        channelRegistered[0] = true;
        racy.sweep(); // retry lands now
        assertEquals(1, lateDeliveries.size(), "el sweep reintenta el HELLO");

        // Complete the handshake against the retried HELLO
        ChunkReassembler reassembler = new ChunkReassembler(1024 * 1024, 8);
        HelloMsg hello = null;
        for (byte[] frame : lateDeliveries.get(0).frames()) {
            FrameHeader h = FrameCodec.decode(frame, signer, WireProtocol.HANDSHAKE_NONCE, true);
            byte[] body = reassembler.accept(h, FrameCodec.body(frame));
            if (body != null) {
                WireTypeRegistry reg = new WireTypeRegistry();
                reg.register(HelloMsg.TYPE);
                hello = (HelloMsg) reg.decode(body).message();
            }
        }
        assertNotNull(hello);
        byte[] ackBody = HelloAckMsg.TYPE.encodeMessage(new HelloAckMsg(1, 1, "proxy", 7L, Map.of()));
        for (byte[] frame : Chunker.split(ackBody, false, 9_500, signer, WireProtocol.HANDSHAKE_NONCE)) {
            racy.onFrame(CARRIER_A, frame);
        }
        assertEquals(SnBridgeState.READY, racy.state());
    }

    @Test
    void closedCoreResolvesEverythingImmediately() {
        core.teardown();
        CompletableFuture<SnDelivery> send = core.send(null,
                TestMsg.TYPE, new TestMsg(CARRIER_A, "tarde"), -1L);
        assertEquals(SnDeliveryResult.EXPIRED_TTL, send.join().result());
        CompletableFuture<Object> request = core.request(ReqMsg.TYPE, new ReqMsg("tarde"),
                RespMsg.TYPE, 5_000L);
        assertTrue(request.isCompletedExceptionally());
        core.openSession(CARRIER_A);
        assertEquals(0, core.sessionCount(), "un core cerrado no abre sesiones");
    }

    @Test
    void duplicateAckIsIgnored() {
        handshake(CARRIER_A);
        long ready = core.counters().received();
        feed(CARRIER_A, HelloAckMsg.TYPE.encodeMessage(
                new HelloAckMsg(1, 9, "impostor", 999L, Map.of())),
                9_100, WireProtocol.HANDSHAKE_NONCE);
        // Signed with handshake nonce but the session is READY: dropped as HMAC/nonce mismatch
        assertEquals(ready, core.counters().received());
        assertEquals(5, core.remoteMsgset(), "el msgset negociado no cambia");
    }
}
