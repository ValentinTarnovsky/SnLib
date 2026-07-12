package com.sn.lib.bridge.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI gate 3 of the spec (section 11): the wireId ledger in docs/SNBRIDGE-SPEC.md section
 * 12 is the historical record of every claimed id. This test parses the REAL ledger and
 * asserts: no duplicate ids in it, every shipped wire type appears in it, and
 * {@link WireIds} cannot drift from the shipped types. Claiming a new snlib id without
 * editing the ledger in the same commit fails the build.
 */
class WireIdLedgerTest {

    /** Every SnWireType shipped by the wire core; EXTEND this list with each new type. */
    private static final List<SnWireType<?>> SHIPPED = List.of(
            HelloMsg.TYPE, HelloAckMsg.TYPE, NackMsg.TYPE, HeartbeatMsg.TYPE);

    @Test
    void ledgerIsDuplicateFreeAndCoversEveryShippedType() throws IOException {
        List<String> ledger = parseLedger();
        assertTrue(ledger.size() >= WireIds.INFRA.size(), "ledger suspiciously short: " + ledger);

        Set<String> unique = new HashSet<>(ledger);
        assertEquals(ledger.size(), unique.size(),
                "the SNBRIDGE-SPEC.md section 12 ledger has duplicate ids: " + ledger);

        for (SnWireType<?> type : SHIPPED) {
            assertTrue(unique.contains(type.wireId()),
                    "'" + type.wireId() + "' is shipped but missing from the spec ledger"
                            + " (add it in the same commit that claims it)");
        }
    }

    @Test
    void wireIdsClassMatchesShippedTypes() {
        Set<String> shippedIds = new HashSet<>(SHIPPED.size());
        for (SnWireType<?> type : SHIPPED) {
            shippedIds.add(type.wireId());
        }
        assertEquals(shippedIds, WireIds.INFRA,
                "WireIds.INFRA does not match the TYPE constants actually shipped");
    }

    @Test
    void shippedTypesCannotDoubleClaimAnId() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(SHIPPED.toArray(SnWireType<?>[]::new)); // throws on any double claim
        for (SnWireType<?> type : SHIPPED) {
            assertTrue(type.wireId().startsWith(WireIds.RESERVED_PREFIX),
                    type.wireId() + " should use the reserved infra prefix");
        }
    }

    /** Extracts the ids from the fenced ledger block under spec section 12 (surefire cwd = basedir). */
    private static List<String> parseLedger() throws IOException {
        Path spec = Path.of("docs", "SNBRIDGE-SPEC.md");
        assertTrue(Files.exists(spec), "could not find " + spec.toAbsolutePath());
        String text = Files.readString(spec, StandardCharsets.UTF_8);
        int anchor = text.indexOf("WireId ledger");
        assertTrue(anchor > 0, "the spec lost the 'WireId ledger' section");
        int open = text.indexOf("```", anchor);
        int close = text.indexOf("```", open + 3);
        assertTrue(open > 0 && close > open, "the ledger is no longer a parseable code block");
        String block = text.substring(open, close);
        Matcher matcher = Pattern.compile("snlib:[a-z_/]+").matcher(block);
        List<String> ids = new ArrayList<>(16);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }
}
