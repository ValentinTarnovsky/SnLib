package com.sn.lib.bridge.wire;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden byte corpus: japicmp guards zero bytes of wire format, THIS does. Every message
 * type has its exact bytes pinned here; if an edit to a codec changes the bytes of an
 * EXISTING (wireId, version) pair, this test fails and the change is a wire break, not a
 * refactor. New versions append NEW fixtures; old fixtures are never edited or deleted
 * (that is the byte-level mirror of the never-reuse-a-wireId ledger rule).
 */
class GoldenFixturesTest {

    // --- Fixtures v1 (frame v1, msgset infra v1), pinned 2026-07-11 ---

    private static final String HELLO_V1 =
            "0000000b736e6c69623a68656c6c6f0001000000310101000300000005312e322e3011223344"
            + "55667788000200000007616374696f6e73000200000007636f6e736f6c650001";
    private static final HelloMsg HELLO_MSG = new HelloMsg(1, 1, 3, "1.2.0",
            0x1122334455667788L, Map.of("console", 1, "actions", 2));

    private static final String HELLO_ACK_V1 =
            "0000000f736e6c69623a68656c6c6f5f61636b00010000002301000300000005312e322e3001"
            + "02030405060708000100000007636f6e736f6c650001";
    private static final HelloAckMsg HELLO_ACK_MSG = new HelloAckMsg(1, 3, "1.2.0",
            0x0102030405060708L, Map.of("console", 1));

    private static final String NACK_V1 =
            "0000000a736e6c69623a6e61636b00010000002a0000004d00000016736e637265646974733a"
            + "6f70656e5f636f6e6669726d03000000077061747465726e";
    private static final NackMsg NACK_MSG = new NackMsg(77, "sncredits:open_confirm",
            NackReason.DENIED_BY_ALLOWLIST, "pattern");

    private static final String HEARTBEAT_V1 =
            "0000000f736e6c69623a68656172746265617400010000000800000000075bcd15";
    private static final HeartbeatMsg HEARTBEAT_MSG = new HeartbeatMsg(123456789L);

    private static final String FRAME_V1 =
            "b501000a0b0c0d000000018ec7472158f10d9d4314dce1ed068864616263";
    private static final HmacSigner FRAME_SIGNER =
            new HmacSigner("fixture-secret".getBytes(StandardCharsets.UTF_8));

    @Test
    void encodersAreByteStable() {
        assertArrayEquals(fromHex(HELLO_V1), HelloMsg.TYPE.encodeMessage(HELLO_MSG), "snlib:hello");
        assertArrayEquals(fromHex(HELLO_ACK_V1), HelloAckMsg.TYPE.encodeMessage(HELLO_ACK_MSG), "snlib:hello_ack");
        assertArrayEquals(fromHex(NACK_V1), NackMsg.TYPE.encodeMessage(NACK_MSG), "snlib:nack");
        assertArrayEquals(fromHex(HEARTBEAT_V1), HeartbeatMsg.TYPE.encodeMessage(HEARTBEAT_MSG), "snlib:heartbeat");
    }

    @Test
    void oldBytesAlwaysDecode() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(HelloMsg.TYPE, HelloAckMsg.TYPE, NackMsg.TYPE, HeartbeatMsg.TYPE);
        assertEquals(HELLO_MSG, registry.decode(fromHex(HELLO_V1)).message());
        assertEquals(HELLO_ACK_MSG, registry.decode(fromHex(HELLO_ACK_V1)).message());
        assertEquals(NACK_MSG, registry.decode(fromHex(NACK_V1)).message());
        assertEquals(HEARTBEAT_MSG, registry.decode(fromHex(HEARTBEAT_V1)).message());
    }

    @Test
    void frameLayoutIsByteStable() {
        byte[] expected = fromHex(FRAME_V1);
        byte[] actual = FrameCodec.encode(0x0A0B0C0D, 0, 1, false, false,
                "abc".getBytes(StandardCharsets.UTF_8), 0, 3, FRAME_SIGNER, WireProtocol.HANDSHAKE_NONCE);
        assertArrayEquals(expected, actual, "frame v1");

        FrameHeader header = FrameCodec.decode(expected, FRAME_SIGNER, WireProtocol.HANDSHAKE_NONCE, false);
        assertEquals(0x0A0B0C0D, header.msgId());
        assertEquals(false, header.toProxy());
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), FrameCodec.body(expected));
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex, i * 2, i * 2 + 2, 16);
        }
        return out;
    }
}

