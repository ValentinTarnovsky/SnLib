package com.sn.lib.bridge.wire;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnBufTest {

    @Test
    void roundTripsEveryPrimitive() {
        UUID uuid = UUID.fromString("11d1c9be-7b74-4b16-8d0e-5a0c1f2a3b4c");
        SnBuf w = SnBuf.forWrite(16);
        w.u8(0);
        w.u8(255);
        w.u16(0xFFFF);
        w.i32(Integer.MIN_VALUE);
        w.i64(Long.MAX_VALUE);
        w.f32(3.14f);
        w.f64(-2.718281828);
        w.bool(true);
        w.bool(false);
        w.str("hola ñandú 🎉");
        w.str("");
        w.bytes(new byte[] {1, 2, 3});
        w.uuid(uuid);

        SnBuf r = SnBuf.forRead(w.toByteArray());
        assertEquals(0, r.u8());
        assertEquals(255, r.u8());
        assertEquals(0xFFFF, r.u16());
        assertEquals(Integer.MIN_VALUE, r.i32());
        assertEquals(Long.MAX_VALUE, r.i64());
        assertEquals(3.14f, r.f32());
        assertEquals(-2.718281828, r.f64());
        assertTrue(r.bool());
        assertEquals(false, r.bool());
        assertEquals("hola ñandú 🎉", r.str());
        assertEquals("", r.str());
        assertArrayEquals(new byte[] {1, 2, 3}, r.bytes());
        assertEquals(uuid, r.uuid());
        assertEquals(0, r.remaining());
    }

    @Test
    void bigEndianLayoutIsStable() {
        SnBuf w = SnBuf.forWrite(8);
        w.u16(0x0102);
        w.i32(0x03040506);
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, w.toByteArray());
    }

    @Test
    void strHasNoWriteUtf64kCeiling() {
        // The whole point vs writeUTF: > 64KB strings must survive
        String big = "x".repeat(70_000);
        SnBuf w = SnBuf.forWrite(16);
        w.str(big);
        assertEquals(big, SnBuf.forRead(w.toByteArray()).str());
    }

    @Test
    void truncatedReadsThrowInsteadOfGarbage() {
        SnBuf w = SnBuf.forWrite(8);
        w.i32(1234);
        byte[] data = w.toByteArray();
        byte[] cut = new byte[2];
        System.arraycopy(data, 0, cut, 0, 2);
        assertThrows(SnWireException.class, () -> SnBuf.forRead(cut).i32());
    }

    @Test
    void strRejectsNegativeAndAbsurdLengths() {
        SnBuf w = SnBuf.forWrite(8);
        w.i32(-5);
        assertThrows(SnWireException.class, () -> SnBuf.forRead(w.toByteArray()).str());

        SnBuf w2 = SnBuf.forWrite(8);
        w2.i32(SnBuf.MAX_FIELD_BYTES + 1);
        assertThrows(SnWireException.class, () -> SnBuf.forRead(w2.toByteArray()).str());
    }

    @Test
    void rangeChecksOnWrite() {
        SnBuf w = SnBuf.forWrite(8);
        assertThrows(SnWireException.class, () -> w.u8(256));
        assertThrows(SnWireException.class, () -> w.u8(-1));
        assertThrows(SnWireException.class, () -> w.u16(0x10000));
        assertThrows(SnWireException.class, () -> w.str(null));
        assertThrows(SnWireException.class, () -> w.uuid(null));
    }

    @Test
    void modesAreEnforced() {
        SnBuf w = SnBuf.forWrite(8);
        assertThrows(SnWireException.class, w::u8);
        SnBuf r = SnBuf.forRead(new byte[4]);
        assertThrows(SnWireException.class, () -> r.u8(1));
    }

    @Test
    void readSliceSkipsTrailingBytesOfNewerEmitters() {
        SnBuf w = SnBuf.forWrite(16);
        w.i32(7);          // known field
        w.f64(9.5);        // "new" field the old decoder ignores
        byte[] payload = w.toByteArray();

        SnBuf outer = SnBuf.forWrite(32);
        outer.i32(payload.length);
        outer.raw(payload, 0, payload.length);
        outer.str("despues");

        SnBuf r = SnBuf.forRead(outer.toByteArray());
        SnBuf slice = r.readSlice(r.i32());
        assertEquals(7, slice.i32());
        // old decoder stops here; outer cursor already sits past the whole slice
        assertEquals("despues", r.str());
        assertEquals(8, slice.remaining());
    }

    @Test
    void absurdSliceLengthCannotOverflowTheBoundsCheck() {
        // A wire-derived length near Integer.MAX_VALUE must throw truncation, not wrap
        // pos + n into a negative and slip past the check
        SnBuf r = SnBuf.forRead(new byte[20]);
        r.i32();
        assertThrows(SnWireException.class, () -> r.readSlice(Integer.MAX_VALUE));
        assertEquals(16, r.remaining()); // buffer intact after the rejection
    }

    @Test
    void reserveAndPatchBackfillsLengths() {
        SnBuf w = SnBuf.forWrite(8);
        int at = w.reserveI32();
        w.str("abc");
        w.patchI32(at, w.size() - at - 4);
        SnBuf r = SnBuf.forRead(w.toByteArray());
        assertEquals(7, r.i32()); // 4 (len) + 3 (utf)
        assertEquals("abc", r.str());
    }
}
