package com.sn.lib.bridge.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response flag is what stops the heartbeat echo loop: only a fresh ping (flag
 * clear) is echoed; the echo is flagged as a response, so the other side must NOT echo
 * it again. This locks the frame-level contract the two cores rely on.
 */
class HeartbeatLoopTest {

    private static final HmacSigner SIGNER =
            new HmacSigner("hb-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void freshPingIsNotFlaggedResponseButEchoIs() {
        // A fresh ping has the response flag clear
        byte[] ping = Chunker.split(HeartbeatMsg.TYPE.encodeMessage(new HeartbeatMsg(7L)),
                true, false, 1, SIGNER, 0L).get(0);
        assertFalse(FrameCodec.decode(ping, SIGNER, 0L, true).isResponse());

        // The echo a receiver sends back carries the response flag
        byte[] echo = Chunker.split(HeartbeatMsg.TYPE.encodeMessage(new HeartbeatMsg(7L)),
                true, true, 1, SIGNER, 0L).get(0);
        assertTrue(FrameCodec.decode(echo, SIGNER, 0L, true).isResponse());
    }

    @Test
    void echoPreservesMsgIdForCorrelation() {
        byte[] echo = Chunker.split(HeartbeatMsg.TYPE.encodeMessage(new HeartbeatMsg(42L)),
                false, true, 999, SIGNER, 0L).get(0);
        FrameHeader header = FrameCodec.decode(echo, SIGNER, 0L, false);
        assertEquals(999, header.msgId());
        assertTrue(header.isResponse());
    }
}
