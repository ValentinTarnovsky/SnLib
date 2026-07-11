package com.sn.lib.bridge.wire;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkingTest {

    private static final HmacSigner SIGNER = new HmacSigner("secreto".getBytes(StandardCharsets.UTF_8));

    private static byte[] randomBody(int size) {
        byte[] body = new byte[size];
        new Random(42).nextBytes(body); // seeded: tests stay deterministic
        return body;
    }

    private static byte[] reassemble(List<byte[]> frames, ChunkReassembler reassembler) {
        byte[] result = null;
        for (byte[] frame : frames) {
            FrameHeader h = FrameCodec.decode(frame, SIGNER, 0L, false);
            result = reassembler.accept(h, FrameCodec.body(frame));
        }
        return result;
    }

    @Test
    void singleChunkFastPath() {
        byte[] body = randomBody(1000);
        List<byte[]> frames = Chunker.split(body, false, 7, SIGNER, 0L);
        assertEquals(1, frames.size());
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertArrayEquals(body, reassemble(frames, r));
        assertEquals(0, r.pendingCount());
    }

    @Test
    void splitsToBackendAtSmallCapAndReassembles() {
        // 100KB in the CAPPED direction (proxy -> backend): the SnCredits config case
        byte[] body = randomBody(100_000);
        List<byte[]> frames = Chunker.split(body, false, 1, SIGNER, 0L);
        assertEquals(5, frames.size()); // ceil(100000 / 24576)
        for (byte[] frame : frames) {
            assertEquals(true, frame.length <= WireProtocol.HEADER_LENGTH + WireProtocol.MAX_CHUNK_BODY_TO_BACKEND);
        }
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertArrayEquals(body, reassemble(frames, r));
    }

    @Test
    void toProxyDirectionUsesBigChunks() {
        byte[] body = randomBody(100_000);
        List<byte[]> frames = Chunker.split(body, true, 1, SIGNER, 0L);
        assertEquals(1, frames.size()); // fits the ~1MB clientbound budget in one frame
    }

    @Test
    void interleavedMessagesReassembleIndependently() {
        byte[] bodyA = randomBody(60_000);
        byte[] bodyB = randomBody(30_000);
        List<byte[]> framesA = Chunker.split(bodyA, false, 100, SIGNER, 0L);
        List<byte[]> framesB = Chunker.split(bodyB, false, 200, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);

        // A0, B0, A1, B1(done), A2(done)
        assertNull(feed(r, framesA.get(0)));
        assertNull(feed(r, framesB.get(0)));
        assertNull(feed(r, framesA.get(1)));
        assertArrayEquals(bodyB, feed(r, framesB.get(1)));
        assertArrayEquals(bodyA, feed(r, framesA.get(2)));
        assertEquals(0, r.pendingCount());
    }

    private static byte[] feed(ChunkReassembler r, byte[] frame) {
        FrameHeader h = FrameCodec.decode(frame, SIGNER, 0L, false);
        return r.accept(h, FrameCodec.body(frame));
    }

    @Test
    void outOfOrderChunkKillsTheMessage() {
        byte[] body = randomBody(60_000);
        List<byte[]> frames = Chunker.split(body, false, 5, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertNull(feed(r, frames.get(0)));
        assertThrows(SnWireException.class, () -> feed(r, frames.get(2))); // skipped index 1
        assertEquals(0, r.pendingCount()); // state discarded, not poisoned
    }

    @Test
    void lateChunkWithoutStartIsRejected() {
        byte[] body = randomBody(60_000);
        List<byte[]> frames = Chunker.split(body, false, 5, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertThrows(SnWireException.class, () -> feed(r, frames.get(1)));
    }

    @Test
    void messageSizeCapIsEnforced() {
        byte[] body = randomBody(60_000);
        List<byte[]> frames = Chunker.split(body, false, 5, SIGNER, 0L);
        ChunkReassembler tiny = new ChunkReassembler(30_000, 8);
        assertNull(feed(tiny, frames.get(0))); // 24576 fits
        assertThrows(SnWireException.class, () -> feed(tiny, frames.get(1))); // would cross 30k
        assertEquals(0, tiny.pendingCount());
    }

    @Test
    void pendingMessagesCapIsEnforced() {
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 2);
        byte[] body = randomBody(50_000);
        assertNull(feed(r, Chunker.split(body, false, 1, SIGNER, 0L).get(0)));
        assertNull(feed(r, Chunker.split(body, false, 2, SIGNER, 0L).get(0)));
        assertThrows(SnWireException.class,
                () -> feed(r, Chunker.split(body, false, 3, SIGNER, 0L).get(0)));
    }

    @Test
    void clearDropsPartialStateOnDisconnect() {
        byte[] body = randomBody(60_000);
        List<byte[]> frames = Chunker.split(body, false, 9, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertNull(feed(r, frames.get(0)));
        assertEquals(1, r.pendingCount());
        r.clear();
        assertEquals(0, r.pendingCount());
        // After the carrier died, a restart of the same msgId from chunk 0 works fine
        assertNull(feed(r, frames.get(0)));
    }

    @Test
    void bufferGrowthStaysClampedToNonPowerOfTwoCap() {
        // cap 100_000: internal buffer starts at 65_536 and must clamp its doubling to the cap
        byte[] body = randomBody(90_000);
        List<byte[]> frames = Chunker.split(body, false, 11, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(100_000, 8);
        assertArrayEquals(body, reassemble(frames, r));
    }

    @Test
    void restartFromChunkZeroReplacesOldState() {
        byte[] body = randomBody(60_000);
        List<byte[]> frames = Chunker.split(body, false, 9, SIGNER, 0L);
        ChunkReassembler r = new ChunkReassembler(8 * 1024 * 1024, 8);
        assertNull(feed(r, frames.get(0)));
        assertNull(feed(r, frames.get(0))); // sender retried from scratch
        assertNull(feed(r, frames.get(1)));
        assertArrayEquals(body, feed(r, frames.get(2)));
    }
}
