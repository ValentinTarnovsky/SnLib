package com.sn.lib.bridge.wire;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    private static final HmacSigner SIGNER = new HmacSigner("secreto-de-prueba".getBytes(StandardCharsets.UTF_8));
    private static final HmacSigner OTHER_SIGNER = new HmacSigner("otro-secreto".getBytes(StandardCharsets.UTF_8));
    private static final byte[] BODY = "cuerpo del mensaje".getBytes(StandardCharsets.UTF_8);

    private static byte[] frame(boolean toProxy, long nonce) {
        return FrameCodec.encode(42, 0, 1, toProxy, BODY, 0, BODY.length, SIGNER, nonce);
    }

    @Test
    void roundTripsHeaderAndBody() {
        byte[] frame = frame(true, 99L);
        FrameHeader h = FrameCodec.decode(frame, SIGNER, 99L, true);
        assertEquals(WireProtocol.FRAME_VERSION, h.frameVersion());
        assertTrue(h.toProxy());
        assertEquals(42, h.msgId());
        assertEquals(0, h.chunkIndex());
        assertEquals(1, h.chunkCount());
        assertArrayEquals(BODY, FrameCodec.body(frame));
    }

    @Test
    void rejectsWrongSecret() {
        byte[] frame = frame(false, 0L);
        assertThrows(SnWireException.class, () -> FrameCodec.decode(frame, OTHER_SIGNER, 0L, false));
    }

    @Test
    void rejectsWrongSessionNonce() {
        byte[] frame = frame(false, 1234L);
        assertThrows(SnWireException.class, () -> FrameCodec.decode(frame, SIGNER, 5678L, false));
    }

    @Test
    void rejectsFlippedDirectionFlag() {
        // Tamper detection: flipping the signed flag breaks the HMAC even when the
        // receiver would accept the claimed direction
        byte[] frame = frame(false, 0L);
        frame[2] ^= WireProtocol.FLAG_TO_PROXY;
        assertThrows(SnWireException.class, () -> FrameCodec.decode(frame, SIGNER, 0L, true));
    }

    @Test
    void rejectsReflectedIntactFrame() {
        // Reflection: the frame is AUTHENTIC and untouched (HMAC valid), but it belongs
        // to the other leg; the receiver-side direction check must kill it
        byte[] toBackend = frame(false, 0L);
        SnWireException ex = assertThrows(SnWireException.class,
                () -> FrameCodec.decode(toBackend, SIGNER, 0L, true));
        assertTrue(ex.getMessage().contains("reflejado"));

        byte[] toProxy = frame(true, 0L);
        assertThrows(SnWireException.class, () -> FrameCodec.decode(toProxy, SIGNER, 0L, false));
    }

    @Test
    void rejectsTamperedBody() {
        byte[] frame = frame(true, 0L);
        frame[frame.length - 1] ^= 0x01;
        assertThrows(SnWireException.class, () -> FrameCodec.decode(frame, SIGNER, 0L, true));
    }

    @Test
    void rejectsGarbageMagicAndTruncation() {
        byte[] frame = frame(true, 0L);
        byte[] badMagic = frame.clone();
        badMagic[0] = 0x00;
        assertThrows(SnWireException.class, () -> FrameCodec.decode(badMagic, SIGNER, 0L, true));
        assertThrows(SnWireException.class, () -> FrameCodec.decode(new byte[10], SIGNER, 0L, true));
        assertThrows(SnWireException.class, () -> FrameCodec.decode(null, SIGNER, 0L, true));
    }

    @Test
    void rejectsUnsupportedFrameVersion() {
        byte[] frame = frame(true, 0L);
        frame[1] = (byte) (WireProtocol.FRAME_VERSION + 1);
        assertThrows(SnWireException.class, () -> FrameCodec.decode(frame, SIGNER, 0L, true));
    }

    @Test
    void emptySecretIsRefusedUpFront() {
        assertThrows(SnWireException.class, () -> new HmacSigner(new byte[0]));
        assertThrows(SnWireException.class, () -> new HmacSigner(null));
    }

    @Test
    void constantTimeCompareBehaves() {
        byte[] a = {1, 2, 3};
        assertTrue(HmacSigner.tagsEqual(a, new byte[] {1, 2, 3}));
        assertFalse(HmacSigner.tagsEqual(a, new byte[] {1, 2, 4}));
        assertFalse(HmacSigner.tagsEqual(a, new byte[] {1, 2}));
    }
}
