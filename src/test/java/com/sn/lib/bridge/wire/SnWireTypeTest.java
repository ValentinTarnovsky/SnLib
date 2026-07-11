package com.sn.lib.bridge.wire;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnWireTypeTest {

    private record Sample(UUID player, String item, double price) {
        static final SnWireType<Sample> TYPE = SnWireType.of(
                "test:sample", 2,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.item());
                    buf.f64(m.price());
                },
                (buf, version) -> {
                    UUID p = buf.uuid();
                    String item = buf.str();
                    double price = version >= 2 ? buf.f64() : 0.0;
                    return new Sample(p, item, price);
                });
    }

    private static final UUID UUID_A = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");

    @Test
    void selfTestPassesOnHonestCodec() {
        Sample decoded = Sample.TYPE.selfTest(new Sample(UUID_A, "key_vote", 500.0));
        assertEquals("key_vote", decoded.item());
    }

    @Test
    void selfTestCatchesFieldOrderDrift() {
        SnWireType<Sample> drifted = SnWireType.of(
                "test:drifted", 1,
                (buf, m) -> {
                    buf.str(m.item());   // encoder: str first
                    buf.uuid(m.player());
                    buf.f64(m.price());
                },
                (buf, version) -> new Sample(buf.uuid(), buf.str(), buf.f64())); // decoder: uuid first
        assertThrows(SnWireException.class,
                () -> drifted.selfTest(new Sample(UUID_A, "abc", 1.0)));
    }

    @Test
    void selfTestCatchesUnreadTrailingBytes() {
        SnWireType<Sample> lazy = SnWireType.of(
                "test:lazy", 1,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.item());
                    buf.f64(m.price());
                },
                (buf, version) -> new Sample(buf.uuid(), buf.str(), 0.0) /* forgets f64 */);
        SnWireException ex = assertThrows(SnWireException.class,
                () -> lazy.selfTest(new Sample(UUID_A, "abc", 0.0)));
        assertTrue(ex.getMessage().contains("sin leer"));
    }

    @Test
    void oldDecoderReadsNewEmitterViaLengthPrefix() {
        // v2 emitter writes uuid+str+f64; a v1-era decoder (reads uuid+str only) must survive
        SnWireType<Sample> v1Decoder = SnWireType.of(
                "test:sample", 1,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.item());
                },
                (buf, version) -> new Sample(buf.uuid(), buf.str(), 0.0));

        byte[] v2Body = Sample.TYPE.encodeMessage(new Sample(UUID_A, "key", 9.99));

        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(v1Decoder);
        WireTypeRegistry.DecodedMessage decoded = registry.decode(v2Body);
        assertEquals(2, decoded.emitterVersion());
        Sample msg = (Sample) decoded.message();
        assertEquals(UUID_A, msg.player());
        assertEquals("key", msg.item());
        assertEquals(0.0, msg.price()); // the trailing f64 was skipped by the slice, not misread
    }

    @Test
    void newDecoderBranchesOnOldEmitterVersion() {
        SnWireType<Sample> v1Emitter = SnWireType.of(
                "test:sample", 1,
                (buf, m) -> {
                    buf.uuid(m.player());
                    buf.str(m.item());
                },
                (buf, version) -> new Sample(buf.uuid(), buf.str(), 0.0));
        byte[] v1Body = v1Emitter.encodeMessage(new Sample(UUID_A, "key", 0.0));

        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(Sample.TYPE); // v2 decoder branches on version >= 2 for price
        Sample msg = (Sample) registry.decode(v1Body).message();
        assertEquals(0.0, msg.price());
        assertEquals("key", msg.item());
    }

    @Test
    void registryRejectsDuplicateWireIds() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(Sample.TYPE);
        SnWireType<Sample> impostor = SnWireType.of(
                "test:sample", 1,
                (buf, m) -> buf.str(m.item()),
                (buf, version) -> new Sample(UUID_A, buf.str(), 0.0));
        assertThrows(SnWireException.class, () -> registry.register(impostor));
    }

    @Test
    void unknownWireIdCarriesTheIdForNacks() {
        WireTypeRegistry registry = new WireTypeRegistry();
        byte[] body = Sample.TYPE.encodeMessage(new Sample(UUID_A, "x", 1.0));
        UnknownWireIdException ex = assertThrows(UnknownWireIdException.class,
                () -> registry.decode(body));
        assertEquals("test:sample", ex.wireId());
    }

    @Test
    void lyingBodyLenIsRejectedUpFront() {
        // Body declares a huge field block that is not actually there
        SnBuf forged = SnBuf.forWrite(32);
        forged.str("test:sample");
        forged.u16(1);
        forged.i32(Integer.MAX_VALUE);
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(Sample.TYPE);
        SnWireException ex = assertThrows(SnWireException.class,
                () -> registry.decode(forged.toByteArray()));
        assertTrue(ex.getMessage().contains("bodyLen invalido"));
    }

    @Test
    void wireIdFormatIsValidated() {
        SnWireType.Encoder<Sample> enc = (buf, m) -> { };
        SnWireType.Decoder<Sample> dec = (buf, v) -> null;
        assertThrows(SnWireException.class, () -> SnWireType.of("SinNamespace", 1, enc, dec));
        assertThrows(SnWireException.class, () -> SnWireType.of("Mayus:cula", 1, enc, dec));
        assertThrows(SnWireException.class, () -> SnWireType.of(":vacio", 1, enc, dec));
        assertThrows(SnWireException.class, () -> SnWireType.of("test:ok", 0, enc, dec));
    }
}
