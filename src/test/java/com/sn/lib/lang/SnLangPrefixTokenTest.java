package com.sn.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Literal prefix-token detection: the defense-in-depth scan that warns once per load when
 * a language value embeds the token SnLib already prepends on its own. Exercises the pure
 * counting helpers, which count keys (never occurrences) and ignore null lines.
 */
class SnLangPrefixTokenTest {

    private static final String TOKEN = SnLang.LITERAL_PREFIX_TOKEN;

    @Test
    void carriesTokenDetectsAnyLine() {
        assertTrue(SnLang.carriesLiteralPrefixToken(List.of(TOKEN + "Hello")));
        assertTrue(SnLang.carriesLiteralPrefixToken(List.of("clean", "line " + TOKEN + " here")));
    }

    @Test
    void carriesTokenIgnoresCleanValues() {
        assertFalse(SnLang.carriesLiteralPrefixToken(List.of("clean", "also clean")));
        assertFalse(SnLang.carriesLiteralPrefixToken(List.of()));
    }

    @Test
    void carriesTokenToleratesNullLinesAndValue() {
        assertFalse(SnLang.carriesLiteralPrefixToken(Arrays.asList("clean", null)));
        assertFalse(SnLang.carriesLiteralPrefixToken(null));
    }

    @Test
    void countsAffectedKeysNotOccurrences() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        byKey.put("welcome", List.of(TOKEN + "Welcome"));
        byKey.put("help", List.of("no token here"));
        byKey.put("lore", List.of("line " + TOKEN, "another " + TOKEN, "third " + TOKEN));
        assertEquals(2, SnLang.countKeysWithLiteralPrefixToken(byKey));
    }

    @Test
    void countIsZeroWhenNoValueCarriesTheToken() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        byKey.put("a", List.of("plain"));
        byKey.put("b", List.of("also plain", "still plain"));
        assertEquals(0, SnLang.countKeysWithLiteralPrefixToken(byKey));
    }
}
