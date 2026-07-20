package com.sn.lib.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code [noprefix]} opt-out detection: a single-line lang value whose leading tag run
 * carries the tag is sent without the configured prefix. Exercises the pure scanning
 * helper; the tag itself is stripped later by the SnText render.
 */
class SnLangNoPrefixTagTest {

    @Test
    void leadingTagOptsOut() {
        assertTrue(SnLang.skipsPrefix("[noprefix]Hello"));
    }

    @Test
    void anyOrderAmongLeadingTags() {
        assertTrue(SnLang.skipsPrefix("[rgb][noprefix]Hello"));
        assertTrue(SnLang.skipsPrefix("[noprefix][center]Hello"));
        assertTrue(SnLang.skipsPrefix("[center][small][noprefix]Hello"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(SnLang.skipsPrefix("[NoPrefix]Hello"));
        assertTrue(SnLang.skipsPrefix("[NOPREFIX]Hello"));
    }

    @Test
    void plainAndTaggedLinesWithoutTheTagKeepThePrefix() {
        assertFalse(SnLang.skipsPrefix("Hello"));
        assertFalse(SnLang.skipsPrefix("[rgb]Hello"));
        assertFalse(SnLang.skipsPrefix("[center][small]Hello"));
    }

    @Test
    void midLineTagDoesNotOptOut() {
        assertFalse(SnLang.skipsPrefix("Hello [noprefix]"));
        assertFalse(SnLang.skipsPrefix("&7[noprefix]Hello"));
    }
}
