package com.sn.lib.bridge.wire;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloProtocolTest {

    @Test
    void helloSelfTest() {
        HelloMsg.TYPE.selfTest(new HelloMsg(1, 1, 3, "1.2.0", 0x1122334455667788L,
                Map.of("console", 1, "actions", 2, "bossbar", 1)));
        HelloMsg.TYPE.selfTest(new HelloMsg(1, 2, 1, "1.2.0", -1L, Map.of()));
    }

    @Test
    void helloAckSelfTest() {
        HelloAckMsg.TYPE.selfTest(new HelloAckMsg(1, 3, "1.2.0", 42L, Map.of("console", 1)));
        HelloAckMsg.TYPE.selfTest(new HelloAckMsg(1, 1, "", 0L, Map.of()));
    }

    @Test
    void nackSelfTest() {
        NackMsg.TYPE.selfTest(new NackMsg(77, "sncredits:open_confirm",
                NackReason.DENIED_BY_ALLOWLIST, "pattern mismatch"));
        NackMsg.TYPE.selfTest(new NackMsg(0, "", NackReason.MALFORMED, ""));
    }

    @Test
    void heartbeatSelfTest() {
        HeartbeatMsg.TYPE.selfTest(new HeartbeatMsg(123456789L));
    }

    @Test
    void nackReasonUsesExplicitIdsNotOrdinals() {
        // The enum declaration order must NEVER matter on the wire
        assertEquals(1, NackReason.UNSUPPORTED_MSGSET.id());
        assertEquals(2, NackReason.UNKNOWN_WIRE_ID.id());
        assertEquals(3, NackReason.DENIED_BY_ALLOWLIST.id());
        assertEquals(4, NackReason.UNSUPPORTED_VERB.id());
        assertEquals(5, NackReason.MALFORMED.id());
        assertEquals(6, NackReason.INTERNAL_ERROR.id());
    }

    @Test
    void unknownNackReasonDecodesAsUnrecognizedNotError() {
        assertEquals(NackReason.UNRECOGNIZED, NackReason.fromId(200));
    }

    @Test
    void capabilitiesEncodeSortedForDeterminism() {
        Map<String, Integer> unordered = Map.of("zulu", 1, "alpha", 2, "mike", 3);
        byte[] a = HelloMsg.TYPE.encodeMessage(new HelloMsg(1, 1, 1, "v", 0L, unordered));
        byte[] b = HelloMsg.TYPE.encodeMessage(new HelloMsg(1, 1, 1, "v",
                0L, new java.util.LinkedHashMap<>(Map.of("mike", 3, "zulu", 1, "alpha", 2))));
        assertTrue(java.util.Arrays.equals(a, b), "the source map order must not affect the bytes");
    }

    @Test
    void infraIdsUseReservedPrefix() {
        // Ledger membership and duplicate-freedom live in WireIdLedgerTest; this only
        // pins the reserved-prefix convention
        for (String id : WireIds.INFRA) {
            assertTrue(id.startsWith(WireIds.RESERVED_PREFIX));
        }
    }
}
