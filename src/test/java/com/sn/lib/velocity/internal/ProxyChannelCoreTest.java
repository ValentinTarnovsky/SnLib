package com.sn.lib.velocity.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sn.lib.bridge.SnDelivery;
import com.sn.lib.bridge.SnDeliveryResult;
import com.sn.lib.bridge.wire.ChunkReassembler;
import com.sn.lib.bridge.wire.Chunker;
import com.sn.lib.bridge.wire.FrameCodec;
import com.sn.lib.bridge.wire.FrameHeader;
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
 * Exercises the proxy-side state machine against a simulated BACKEND: the backend
 * initiates HELLO, the core answers ACK, respond() correlates by msgId, sends route by
 * server name and queue until a session exists.
 */
class ProxyChannelCoreTest {

    private record ShopClick(UUID player, String item) {
        static final SnWireType<ShopClick> TYPE = SnWireType.of("test:shop_click", 1,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.item());
                },
                (buf, v) -> new ShopClick(buf.uuid(), buf.str()));
    }

    private record ReqConfig(String q) {
        static final SnWireType<ReqConfig> TYPE = SnWireType.of("test:req_config", 1,
                (buf, m) -> buf.str(m.q()),
                (buf, v) -> new ReqConfig(buf.str()));
    }

    private record SyncConfig(String blob) {
        static final SnWireType<SyncConfig> TYPE = SnWireType.of("test:sync_config", 1,
                (buf, m) -> buf.str(m.blob()),
                (buf, v) -> new SyncConfig(buf.str()));
    }

    private static final UUID CARRIER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final HmacSigner signer = new HmacSigner("proxy-secret".getBytes(StandardCharsets.UTF_8));
    private final AtomicInteger msgIds = new AtomicInteger(500);
    private final AtomicLong clock = new AtomicLong(5_000_000L);
    private final List<Delivered> deliveries = new ArrayList<>();
    private final List<String> dispatched = new ArrayList<>();
    private ProxyChannelCore core;
    private long backendNonce;
    private long sessionNonce;

    private record Delivered(UUID carrier, List<byte[]> frames) {
    }

    @BeforeEach
    void setUp() {
        core = new ProxyChannelCore("test", 3, "1.2.0", signer,
                msgIds::incrementAndGet, clock::get,
                (carrier, serverName, frames) -> {
                    deliveries.add(new Delivered(carrier, frames));
                    return true;
                },
                (type, carrier, serverName, message) ->
                        dispatched.add(type.wireId() + "@" + serverName),
                new ProxyChannelCore.Log() {
                    @Override
                    public void warn(String message) {
                    }

                    @Override
                    public void debug(java.util.function.Supplier<String> message) {
                    }
                },
                5_000L, 4, 1024 * 1024, 8);
        core.registry().register(ShopClick.TYPE, ReqConfig.TYPE, SyncConfig.TYPE);
    }

    /** Backend side: sends HELLO, consumes the ACK, learns the session nonce. */
    private void backendHandshake(String serverName) {
        backendNonce = 0x1234_5678_9ABCL;
        HelloMsg hello = new HelloMsg(1, WireProtocol.FRAME_VERSION, 7, "backend-1.2.0",
                backendNonce, Map.of("bossbar", 1));
        int before = deliveries.size();
        feed(serverName, HelloMsg.TYPE.encodeMessage(hello), 600, WireProtocol.HANDSHAKE_NONCE);
        // Solo la PRIMERA entrega es el ACK (firmado con handshake nonce); lo que el
        // flush de cola mande despues ya viaja con el nonce de sesion
        HelloAckMsg ack = (HelloAckMsg) decodeRange(before, before + 1,
                WireProtocol.HANDSHAKE_NONCE).get(0).message();
        sessionNonce = backendNonce ^ ack.nonce();
        assertEquals(3, ack.msgsetVersion(), "el ACK lleva el msgset del proxy");
    }

    private void feed(String serverName, byte[] body, int msgId, long nonce) {
        for (byte[] frame : Chunker.split(body, true, false, msgId, signer, nonce)) {
            core.onFrame(CARRIER, serverName, frame);
        }
    }

    private record BackendView(int msgId, Object message) {
    }

    /** Decodes proxy->backend deliveries as the backend companion would. */
    private List<BackendView> decodeDelivered(int fromIndex, long nonce) {
        return decodeRange(fromIndex, deliveries.size(), nonce);
    }

    private List<BackendView> decodeRange(int fromIndex, int toIndexExclusive, long nonce) {
        WireTypeRegistry backendRegistry = new WireTypeRegistry();
        backendRegistry.register(HelloMsg.TYPE, HelloAckMsg.TYPE, NackMsg.TYPE,
                ShopClick.TYPE, ReqConfig.TYPE, SyncConfig.TYPE);
        List<BackendView> out = new ArrayList<>();
        ChunkReassembler reassembler = new ChunkReassembler(1024 * 1024, 8);
        for (int i = fromIndex; i < toIndexExclusive; i++) {
            for (byte[] frame : deliveries.get(i).frames()) {
                FrameHeader header = FrameCodec.decode(frame, signer, nonce, false);
                byte[] body = reassembler.accept(header, FrameCodec.body(frame));
                if (body != null) {
                    out.add(new BackendView(header.msgId(), backendRegistry.decode(body).message()));
                }
            }
        }
        return out;
    }

    @Test
    void helloGetsAckAndSessionTracksBackend() {
        backendHandshake("gens");
        assertEquals(1, core.readySessionsOn("gens"));
        ProxyChannelCore.BackendInfo info = core.capabilities("gens");
        assertNotNull(info);
        assertEquals(7, info.msgset());
        assertEquals(Map.of("bossbar", 1), info.capabilities());
        assertEquals(List.of("gens"), core.liveServers());
    }

    @Test
    void helloWithoutFrameOverlapIsRejected() {
        HelloMsg alien = new HelloMsg(99, 120, 1, "future", 1L, Map.of());
        feed("gens", HelloMsg.TYPE.encodeMessage(alien), 601, WireProtocol.HANDSHAKE_NONCE);
        assertEquals(0, core.readySessionsOn("gens"));
        assertNull(core.capabilities("gens"));
    }

    @Test
    void respondCorrelatesWithTheRequestMsgId() {
        core.respond(ReqConfig.TYPE.wireId(), SyncConfig.TYPE,
                (carrier, server, request) -> new SyncConfig("blob-para-" + server));
        backendHandshake("work");
        int before = deliveries.size();
        feed("work", ReqConfig.TYPE.encodeMessage(new ReqConfig("dame")), 777, sessionNonce);
        List<BackendView> answers = decodeDelivered(before, sessionNonce);
        assertEquals(1, answers.size());
        assertEquals(777, answers.get(0).msgId(), "la respuesta viaja con el MISMO msgId");
        assertEquals(new SyncConfig("blob-para-work"), answers.get(0).message());
        assertTrue(dispatched.isEmpty(), "un request servido no pasa por on()");
    }

    @Test
    void throwingResponderAnswersInternalErrorNack() {
        core.respond(ReqConfig.TYPE.wireId(), SyncConfig.TYPE,
                (carrier, server, request) -> {
                    throw new IllegalStateException("db caida");
                });
        backendHandshake("gens");
        int before = deliveries.size();
        feed("gens", ReqConfig.TYPE.encodeMessage(new ReqConfig("dame")), 778, sessionNonce);
        NackMsg nack = (NackMsg) decodeDelivered(before, sessionNonce).get(0).message();
        assertEquals(NackReason.INTERNAL_ERROR, nack.reason());
        assertEquals(778, nack.refMsgId());
    }

    @Test
    void appMessagesDispatchWithServerContext() {
        backendHandshake("gens");
        feed("gens", ShopClick.TYPE.encodeMessage(new ShopClick(CARRIER, "key_vote")),
                779, sessionNonce);
        assertEquals(List.of("test:shop_click@gens"), dispatched);
    }

    @Test
    void sendQueuesUntilTheServerHasASessionThenFlushes() {
        CompletableFuture<SnDelivery> future = core.sendToServer("gens",
                ShopClick.TYPE, new ShopClick(CARRIER, "abrir"), -1L);
        assertFalse(future.isDone());
        assertEquals(1, core.pending());
        backendHandshake("gens");
        assertEquals(SnDeliveryResult.SENT, future.join().result());
        assertEquals(0, core.pending());
    }

    @Test
    void queuedSendForOtherServerDoesNotFlushCrossServer() {
        CompletableFuture<SnDelivery> forWork = core.sendToServer("work",
                ShopClick.TYPE, new ShopClick(CARRIER, "x"), -1L);
        backendHandshake("gens");
        assertFalse(forWork.isDone(), "una sesion de gens no despacha cola de work");
        clock.addAndGet(30_001L);
        core.sweep();
        assertEquals(SnDeliveryResult.EXPIRED_TTL, forWork.join().result());
    }

    @Test
    void wrongNonceFrameIsDroppedAndCounted() {
        backendHandshake("gens");
        // Nonce totalmente ajeno (ni sesion ni handshake): muere como hmacDrop
        feed("gens", ShopClick.TYPE.encodeMessage(new ShopClick(CARRIER, "spoof")), 780, 424242L);
        assertTrue(core.counters().hmacDrops() > 0);
        // Nonce de handshake con algo que NO es HELLO: muere como malformed
        long malformedBefore = core.counters().malformed;
        feed("gens", ShopClick.TYPE.encodeMessage(new ShopClick(CARRIER, "spoof2")),
                781, WireProtocol.HANDSHAKE_NONCE);
        assertTrue(core.counters().malformed > malformedBefore);
        assertTrue(dispatched.isEmpty(), "nada spoofeado llega al dispatcher");
    }

    @Test
    void disconnectDropsTheSession() {
        backendHandshake("gens");
        core.closeCarrier(CARRIER);
        assertEquals(0, core.readySessionsOn("gens"));
        assertNull(core.capabilities("gens"));
    }

    @Test
    void newHelloAfterServerSwitchReplacesTheSession() {
        backendHandshake("gens");
        // El jugador cambio de server: llega un HELLO nuevo desde work por el mismo carrier
        backendHandshake("work");
        assertEquals(0, core.readySessionsOn("gens"));
        assertEquals(1, core.readySessionsOn("work"));
    }

    @Test
    void switchToNonClaimingServerDropsTheStaleSession() {
        // El jugador paso de gens a lobby y lobby NUNCA manda HELLO (no reclama el
        // namespace): sin la limpieza condicional la sesion de gens queda zombie
        backendHandshake("gens");
        core.closeCarrierIfNotOn(CARRIER, "lobby");
        assertEquals(0, core.readySessionsOn("gens"));
        assertNull(core.capabilities("gens"));
        assertTrue(core.liveServers().isEmpty());
    }

    @Test
    void conditionalCloseNeverWipesAFreshSessionOnTheSameServer() {
        // Race: el HELLO del backend nuevo llego ANTES del ServerConnectedEvent; la
        // limpieza condicional debe preservar esa sesion fresca
        backendHandshake("work");
        core.closeCarrierIfNotOn(CARRIER, "work");
        assertEquals(1, core.readySessionsOn("work"));
    }

    @Test
    void teardownResolvesQueuedSends() {
        CompletableFuture<SnDelivery> queued = core.sendToServer("gens",
                ShopClick.TYPE, new ShopClick(CARRIER, "x"), -1L);
        core.teardown();
        assertEquals(SnDeliveryResult.EXPIRED_TTL, queued.join().result());
        CompletableFuture<SnDelivery> late = core.sendToServer("gens",
                ShopClick.TYPE, new ShopClick(CARRIER, "tarde"), -1L);
        assertEquals(SnDeliveryResult.EXPIRED_TTL, late.join().result());
    }
}

