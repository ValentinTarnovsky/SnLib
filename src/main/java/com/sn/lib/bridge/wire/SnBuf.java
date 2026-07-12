package com.sn.lib.bridge.wire;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Growable binary buffer with the primitive vocabulary of the SnBridge wire format.
 * One instance is either in WRITE mode ({@link #forWrite}) or in READ mode
 * ({@link #forRead}/{@link #readSlice}); encoder lambdas receive a write buffer and
 * decoder lambdas a read buffer, so the same method names double as write (with an
 * argument) and read (no argument) - matching the {@link SnWireType} codec style.
 *
 * <p>Wire conventions: big-endian; {@code u8}/{@code u16} are unsigned and range-checked
 * on write; {@code str} is an i32 byte length followed by raw UTF-8 (NOT modified UTF-8,
 * and deliberately NOT capped at 64KB: large payloads rely on frame chunking, not on
 * string limits); {@code bytes} is an i32 length plus raw bytes; UUIDs are two raw longs.
 * Every read is bounds-checked and throws {@link SnWireException} on truncation.</p>
 *
 * <p>Not thread-safe; a buffer lives and dies inside one encode/decode call.</p>
 */
public final class SnBuf {

    /** Sanity cap for a single str/bytes field; the reassembler caps whole messages anyway. */
    static final int MAX_FIELD_BYTES = 8 * 1024 * 1024;

    private byte[] data;
    private int size;
    private int pos;
    private final int limit;
    private final boolean readable;

    private SnBuf(byte[] data, int size, int pos, int limit, boolean readable) {
        this.data = data;
        this.size = size;
        this.pos = pos;
        this.limit = limit;
        this.readable = readable;
    }

    /** New write-mode buffer with the given initial capacity. */
    public static SnBuf forWrite(int initialCapacity) {
        if (initialCapacity < 16) {
            initialCapacity = 16;
        }
        return new SnBuf(new byte[initialCapacity], 0, 0, -1, false);
    }

    /** Read-mode buffer over the whole array (the array is NOT copied). */
    public static SnBuf forRead(byte[] data) {
        if (data == null) {
            throw new SnWireException("SnBuf.forRead: data null");
        }
        return new SnBuf(data, data.length, 0, data.length, true);
    }

    // -------------------------------------------------------
    // Write side
    // -------------------------------------------------------

    public void u8(int value) {
        checkWrite();
        if (value < 0 || value > 0xFF) {
            throw new SnWireException("u8 out of range: " + value);
        }
        ensure(1);
        data[size++] = (byte) value;
    }

    public void u16(int value) {
        checkWrite();
        if (value < 0 || value > 0xFFFF) {
            throw new SnWireException("u16 out of range: " + value);
        }
        ensure(2);
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) value;
    }

    public void i32(int value) {
        checkWrite();
        ensure(4);
        putI32(size, value);
        size += 4;
    }

    public void i64(long value) {
        checkWrite();
        ensure(8);
        data[size++] = (byte) (value >>> 56);
        data[size++] = (byte) (value >>> 48);
        data[size++] = (byte) (value >>> 40);
        data[size++] = (byte) (value >>> 32);
        data[size++] = (byte) (value >>> 24);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) value;
    }

    public void f32(float value) {
        i32(Float.floatToIntBits(value));
    }

    public void f64(double value) {
        i64(Double.doubleToLongBits(value));
    }

    public void bool(boolean value) {
        u8(value ? 1 : 0);
    }

    /** i32 UTF-8 byte length + raw UTF-8 bytes. Null is a caller bug, not a wire state. */
    public void str(String value) {
        checkWrite();
        if (value == null) {
            throw new SnWireException("null str: wire fields do not allow null (use \"\" or a presence bool)");
        }
        byte[] utf = value.getBytes(StandardCharsets.UTF_8);
        if (utf.length > MAX_FIELD_BYTES) {
            throw new SnWireException("str of " + utf.length + " bytes exceeds the field cap of " + MAX_FIELD_BYTES);
        }
        i32(utf.length);
        raw(utf, 0, utf.length);
    }

    /** i32 length + raw bytes. */
    public void bytes(byte[] value) {
        checkWrite();
        if (value == null) {
            throw new SnWireException("null bytes: wire fields do not allow null");
        }
        if (value.length > MAX_FIELD_BYTES) {
            throw new SnWireException("bytes of " + value.length + " exceeds the field cap of " + MAX_FIELD_BYTES);
        }
        i32(value.length);
        raw(value, 0, value.length);
    }

    public void uuid(UUID value) {
        checkWrite();
        if (value == null) {
            throw new SnWireException("null uuid: wire fields do not allow null");
        }
        i64(value.getMostSignificantBits());
        i64(value.getLeastSignificantBits());
    }

    /** Appends raw bytes with no length prefix (frame internals; not for message fields). */
    public void raw(byte[] src, int off, int len) {
        checkWrite();
        ensure(len);
        System.arraycopy(src, off, data, size, len);
        size += len;
    }

    /**
     * Reserves 4 bytes for a length to be patched later via {@link #patchI32}; returns
     * the reservation offset. Used for the body length-prefix backpatch.
     */
    public int reserveI32() {
        checkWrite();
        int at = size;
        i32(0);
        return at;
    }

    /** Overwrites a previously {@link #reserveI32 reserved} i32 in place. */
    public void patchI32(int at, int value) {
        checkWrite();
        if (at < 0 || at + 4 > size) {
            throw new SnWireException("patchI32 outside the buffer: offset " + at + ", size " + size);
        }
        putI32(at, value);
    }

    /** Bytes written so far (write mode). */
    public int size() {
        checkWrite();
        return size;
    }

    /** Copy of the written bytes (write mode). */
    public byte[] toByteArray() {
        checkWrite();
        byte[] out = new byte[size];
        System.arraycopy(data, 0, out, 0, size);
        return out;
    }

    // -------------------------------------------------------
    // Read side
    // -------------------------------------------------------

    public int u8() {
        require(1);
        return data[pos++] & 0xFF;
    }

    public int u16() {
        require(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int i32() {
        require(4);
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long i64() {
        require(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public double f64() {
        return Double.longBitsToDouble(i64());
    }

    public boolean bool() {
        return u8() != 0;
    }

    public String str() {
        int len = i32();
        if (len < 0 || len > MAX_FIELD_BYTES) {
            throw new SnWireException("str with invalid length on the wire: " + len);
        }
        require(len);
        String s = new String(data, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public byte[] bytes() {
        int len = i32();
        if (len < 0 || len > MAX_FIELD_BYTES) {
            throw new SnWireException("bytes with invalid length on the wire: " + len);
        }
        require(len);
        byte[] out = new byte[len];
        System.arraycopy(data, pos, out, 0, len);
        pos += len;
        return out;
    }

    public UUID uuid() {
        return new UUID(i64(), i64());
    }

    /**
     * Read-mode view over the next {@code len} bytes, advancing this buffer past them.
     * This is how the body length-prefix skips trailing fields a newer emitter added:
     * the decoder runs against the slice and leftovers die with it.
     */
    public SnBuf readSlice(int len) {
        if (len < 0) {
            throw new SnWireException("readSlice with negative length: " + len);
        }
        require(len);
        SnBuf slice = new SnBuf(data, pos + len, pos, pos + len, true);
        pos += len;
        return slice;
    }

    /** Bytes left to read (read mode). */
    public int remaining() {
        checkRead();
        return limit - pos;
    }

    // -------------------------------------------------------
    // Internals
    // -------------------------------------------------------

    private void putI32(int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private void ensure(int extra) {
        if (size + extra <= data.length) {
            return;
        }
        int wanted = Math.max(size + extra, data.length * 2);
        byte[] bigger = new byte[wanted];
        System.arraycopy(data, 0, bigger, 0, size);
        data = bigger;
    }

    private void require(int n) {
        checkRead();
        // Overflow-safe: pos <= limit always holds in read mode, so limit - pos >= 0 and a
        // wire-derived n near Integer.MAX_VALUE cannot wrap the comparison
        if (n < 0 || n > limit - pos) {
            throw new SnWireException("Truncated payload: expected " + n + " bytes but " + (limit - pos) + " remain");
        }
    }

    private void checkWrite() {
        if (readable) {
            throw new SnWireException("Write operation on a read-mode SnBuf");
        }
    }

    private void checkRead() {
        if (!readable) {
            throw new SnWireException("Read operation on a write-mode SnBuf");
        }
    }
}
