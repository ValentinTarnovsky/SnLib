package com.sn.lib.bridge.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.sn.lib.bridge.wire.HmacSigner;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.Verbs;
import com.sn.lib.bridge.wire.WireIds;
import com.sn.lib.velocity.internal.ProxyChannelCore;

import com.sn.lib.bridge.wire.NackReason;
import com.sn.lib.bridge.wire.SnNackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wires a proxy ProxyChannelCore to a backend ChannelCore through a shared in-memory
 * link and drives a full verb round trip: proxy request -> backend responder -> ack
 * correlated by msgId + response flag. No Bukkit, no Velocity; the verb executor's real
 * Bukkit calls are stubbed by a plain responder here (VerbService is covered separately
 * by the smoke run).
 */
class VerbRoundTripTest {

    private static final UUID CARRIER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final String SERVER = "gens";

    private final HmacSigner signer = new HmacSigner("verb-secret".getBytes(StandardCharsets.UTF_8));
    private final AtomicInteger proxyIds = new AtomicInteger(1000);
    private final AtomicInteger backendIds = new AtomicInteger(5000);
    private final AtomicLong clock = new AtomicLong(9_000_000L);

    private ProxyChannelCore proxy;
    private ChannelCore backend;

    private void link() {
        // Proxy sink -> backend.onFrame; backend sink -> proxy.onFrame. Both cores run
        // their delivery synchronously, so a direct hand-off models the wire faithfully.
        proxy = new ProxyChannelCore("snlib", 1, "proxy-1.2.0", signer,
                proxyIds::incrementAndGet, clock::get,
                (carrier, serverName, frames) -> {
                    for (byte[] f : frames) {
                        backend.onFrame(carrier, f);
                    }
                    return true;
                },
                (type, carrier, serverName, message) -> { },
                silentProxyLog(), 5_000L, 8, 1024 * 1024, 8);

        backend = new ChannelCore("snlib", 1, "backend-1.2.0", Map.of("console", 1), signer,
                backendIds::incrementAndGet, clock::get,
                (carrier, frames) -> {
                    for (byte[] f : frames) {
                        proxy.onFrame(carrier, SERVER, f);
                    }
                    return true;
                },
                (type, carrier, message) -> { },
                silentBackendLog(), 5_000L, 8, 1024 * 1024, 8);

        proxy.registerTypes(Verbs.Console.TYPE, Verbs.Ack.TYPE);
        backend.registry().register(Verbs.Console.TYPE, Verbs.Ack.TYPE);

        // Backend executes the verb and answers an ack (real VerbService uses Bukkit here)
        backend.respond(WireIds.VERB_CONSOLE, Verbs.Ack.TYPE, (carrier, request) -> {
            Verbs.Console console = (Verbs.Console) request;
            return console.command().startsWith("crates ")
                    ? new Verbs.Ack(Verbs.ACK_DELIVERED, "")
                    : new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST, "no match");
        });

        handshake();
    }

    /** Backend opens the session; proxy answers HELLO_ACK; nonces line up on both sides. */
    private void handshake() {
        backend.openSession(CARRIER); // sends HELLO -> proxy.onFrame -> HELLO_ACK -> backend
    }

    @Test
    void allowedConsoleVerbReturnsDelivered() {
        link();
        CompletableFuture<Object> future = proxy.request(SERVER, Verbs.Console.TYPE,
                new Verbs.Console("crates key give Bob vote 1"), Verbs.Ack.TYPE, 5_000L);
        assertTrue(future.isDone(), "the whole round trip is synchronous in this harness");
        Verbs.Ack ack = (Verbs.Ack) future.join();
        assertEquals(Verbs.ACK_DELIVERED, ack.code());
    }

    @Test
    void deniedConsoleVerbReturnsDeniedAck() {
        link();
        CompletableFuture<Object> future = proxy.request(SERVER, Verbs.Console.TYPE,
                new Verbs.Console("op Bob"), Verbs.Ack.TYPE, 5_000L);
        Verbs.Ack ack = (Verbs.Ack) future.join();
        assertEquals(Verbs.ACK_DENIED_BY_ALLOWLIST, ack.code());
    }

    @Test
    void requestTimesOutWhenBackendNeverAnswers() {
        link();
        // A wire type the backend has no responder for: it drops it, so no response ever
        SnWireType<Verbs.Message> unhandled = Verbs.Message.TYPE;
        proxy.registerTypes(unhandled);
        backend.registry().register(unhandled);
        CompletableFuture<Object> future = proxy.request(SERVER, unhandled,
                new Verbs.Message(CARRIER, "hi"), Verbs.Ack.TYPE, 1_000L);
        clock.addAndGet(1_001L);
        proxy.sweep();
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void backendNackResolvesTypedNotAsTimeout() {
        link();
        // Send a wire id the backend does not know: it answers UNKNOWN_WIRE_ID NACK
        SnWireType<Verbs.Message> alien = Verbs.Message.TYPE;
        proxy.registerTypes(alien);
        // backend does NOT register alien -> UnknownWireIdException -> typed NACK
        CompletableFuture<Object> future = proxy.request(SERVER, alien,
                new Verbs.Message(CARRIER, "hi"), Verbs.Ack.TYPE, 5_000L);
        assertTrue(future.isCompletedExceptionally());
        try {
            future.join();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof SnNackException,
                    "a backend NACK must surface as SnNackException, got " + cause);
            assertEquals(NackReason.UNKNOWN_WIRE_ID, ((SnNackException) cause).reason());
        }
    }

    private static ChannelCore.Log silentBackendLog() {
        return new ChannelCore.Log() {
            @Override
            public void warn(String message) {
            }

            @Override
            public void debug(java.util.function.Supplier<String> message) {
            }
        };
    }

    private static ProxyChannelCore.Log silentProxyLog() {
        return new ProxyChannelCore.Log() {
            @Override
            public void warn(String message) {
            }

            @Override
            public void debug(java.util.function.Supplier<String> message) {
            }
        };
    }
}
