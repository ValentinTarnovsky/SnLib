package com.sn.lib.bridge.wire;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbsTest {

    private static final UUID U = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    @Test
    void everyVerbSelfTests() {
        Verbs.Console.TYPE.selfTest(new Verbs.Console("crates key give Bob vote 1"));
        Verbs.Message.TYPE.selfTest(new Verbs.Message(U, "<red>hi"));
        Verbs.Title.TYPE.selfTest(new Verbs.Title(U, "Title", "Sub", 10, 40, 10));
        Verbs.Actionbar.TYPE.selfTest(new Verbs.Actionbar(U, "bar text"));
        Verbs.Sound.TYPE.selfTest(new Verbs.Sound(U, "BLOCK_NOTE_BLOCK_PLING 1 1"));
        Verbs.Bossbar.TYPE.selfTest(new Verbs.Bossbar(U, Verbs.BAR_SHOW, "keyall",
                "<red>Soon", 0.5f, "RED", "NOTCHED_10"));
        Verbs.Actions.TYPE.selfTest(new Verbs.Actions(U, List.of("[message] hi", "[sound] X 1 1")));
        Verbs.Ack.TYPE.selfTest(new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST, "no match"));
        Verbs.AllowlistReq.TYPE.selfTest(new Verbs.AllowlistReq());
        Verbs.Allowlist.TYPE.selfTest(new Verbs.Allowlist(List.of("say <word>", "kick <player>")));
    }

    @Test
    void emptyCollectionsRoundTrip() {
        Verbs.Actions.TYPE.selfTest(new Verbs.Actions(U, List.of()));
        Verbs.Allowlist.TYPE.selfTest(new Verbs.Allowlist(List.of()));
    }

    @Test
    void allVerbIdsAreInInfraLedger() {
        List<String> ids = List.of(Verbs.Console.TYPE.wireId(), Verbs.Message.TYPE.wireId(),
                Verbs.Title.TYPE.wireId(), Verbs.Actionbar.TYPE.wireId(),
                Verbs.Sound.TYPE.wireId(), Verbs.Bossbar.TYPE.wireId(),
                Verbs.Actions.TYPE.wireId(), Verbs.Ack.TYPE.wireId(),
                Verbs.AllowlistReq.TYPE.wireId(), Verbs.Allowlist.TYPE.wireId());
        for (String id : ids) {
            assertTrue(WireIds.INFRA.contains(id), id + " missing from WireIds.INFRA");
            assertTrue(id.startsWith("snlib:verb/"), id + " should use the verb prefix");
        }
    }
}
