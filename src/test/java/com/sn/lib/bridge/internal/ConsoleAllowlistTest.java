package com.sn.lib.bridge.internal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleAllowlistTest {

    private static ConsoleAllowlist of(String... patterns) {
        return ConsoleAllowlist.parse(List.of(patterns), new ArrayList<>());
    }

    @Test
    void emptyAllowlistDeniesEverything() {
        ConsoleAllowlist allowlist = of();
        assertNull(allowlist.match("say hi"));
        assertNull(allowlist.match("crates key give Bob vote 1"));
    }

    @Test
    void anchoredPatternMatchesExactTokenCount() {
        ConsoleAllowlist allowlist = of("crates key give <player> vote <int:1..64>");
        assertNotNull(allowlist.match("crates key give Bob vote 1"));
        assertNotNull(allowlist.match("crates key give Player_9 vote 64"));
        // one extra token: anchored, so no prefix match
        assertNull(allowlist.match("crates key give Bob vote 1 extra"));
        // one missing token
        assertNull(allowlist.match("crates key give Bob vote"));
    }

    @Test
    void intRangeIsEnforced() {
        ConsoleAllowlist allowlist = of("eco give <player> <int:1..100>");
        assertNotNull(allowlist.match("eco give Bob 1"));
        assertNotNull(allowlist.match("eco give Bob 100"));
        assertNull(allowlist.match("eco give Bob 0"));
        assertNull(allowlist.match("eco give Bob 101"));
        assertNull(allowlist.match("eco give Bob notanumber"));
    }

    @Test
    void playerTokenRejectsInvalidNames() {
        ConsoleAllowlist allowlist = of("kick <player>");
        assertNotNull(allowlist.match("kick Valid_Name"));
        assertNull(allowlist.match("kick name-with-dashes"));
        assertNull(allowlist.match("kick waytoolongusernamehere"));
    }

    @Test
    void literalTokensAreCaseSensitive() {
        ConsoleAllowlist allowlist = of("give diamond");
        assertNotNull(allowlist.match("give diamond"));
        assertNull(allowlist.match("give DIAMOND"));
        assertNull(allowlist.match("GIVE diamond"));
    }

    @Test
    void prefixWildcardIsRejectedAtLoadTime() {
        List<String> invalid = new ArrayList<>();
        ConsoleAllowlist allowlist = ConsoleAllowlist.parse(
                List.of("crates key give *", "say hello"), invalid);
        assertEquals(1, invalid.size());
        assertTrue(invalid.get(0).contains("wildcards are forbidden"));
        // the valid pattern still loaded
        assertNotNull(allowlist.match("say hello"));
        assertEquals(List.of("say hello"), allowlist.effectivePatterns());
    }

    @Test
    void malformedRangeAndUnknownTokenAreRejected() {
        List<String> invalid = new ArrayList<>();
        ConsoleAllowlist.parse(List.of(
                "eco give <player> <int:5>",       // malformed range
                "do <thing>",                       // unknown token
                "ok <player>"), invalid);
        assertEquals(2, invalid.size());
    }

    @Test
    void wordTokenMatchesAnySingleToken() {
        ConsoleAllowlist allowlist = of("warp <word>");
        assertNotNull(allowlist.match("warp spawn"));
        assertNotNull(allowlist.match("warp pvp-arena"));
        assertNull(allowlist.match("warp spawn extra"));
    }
}
