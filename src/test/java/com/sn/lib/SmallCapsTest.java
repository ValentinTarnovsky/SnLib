package com.sn.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.sn.lib.text.SmallCapsUtil;
import com.sn.lib.text.SnText;

/**
 * Small caps mapping: 1:1 glyph substitution with color codes, section-sign sequences
 * and MiniMessage tags skipped verbatim, plus [small] tag composition in SnText.
 */
class SmallCapsTest {

    private static final String SMALL_ALPHABET =
            "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F"
                    + "\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21x\u028F\u1D22";

    @Test
    void lowercaseAlphabetMapsToSmallCaps() {
        assertEquals(SMALL_ALPHABET, SmallCapsUtil.applySmallTag("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    void uppercaseMapsLikeLowercase() {
        assertEquals(SmallCapsUtil.applySmallTag("abcxyz"), SmallCapsUtil.applySmallTag("ABCXYZ"));
    }

    @Test
    void enyeKeepsDefaultGlyph() {
        assertEquals("\u1D00\u00F1\u1D0F", SmallCapsUtil.applySmallTag("a\u00F1o"));
        assertEquals("\u1D00\u00F1\u1D0F", SmallCapsUtil.applySmallTag("A\u00D1O"));
    }

    @Test
    void accentedVowelsLoseAccent() {
        assertEquals("\u1D0D\u1D07\u0274\u1D1C", SmallCapsUtil.applySmallTag("men\u00FA"));
        assertEquals("\u1D00\u1D07\u026A\u1D0F\u1D1C\u1D1C",
                SmallCapsUtil.applySmallTag("\u00E1\u00E9\u00ED\u00F3\u00FA\u00FC"));
        assertEquals("\u1D00\u1D07\u026A\u1D0F\u1D1C\u1D1C",
                SmallCapsUtil.applySmallTag("\u00C1\u00C9\u00CD\u00D3\u00DA\u00DC"));
    }

    @Test
    void digitsSymbolsAndSpacesPassThrough() {
        assertEquals("123 !?/*", SmallCapsUtil.applySmallTag("123 !?/*"));
    }

    @Test
    void legacyColorCodesSkipped() {
        assertEquals("&a\uA731\u029C\u1D0F\u1D18 &l\u1D0D\u1D07\u0274\u1D1C",
                SmallCapsUtil.applySmallTag("&aShop &lMenu"));
    }

    @Test
    void legacyHexCodesSkipped() {
        assertEquals("&#ff9b00\u0262\u1D0F\u029F\u1D05", SmallCapsUtil.applySmallTag("&#ff9b00gold"));
    }

    @Test
    void sectionSignCodesSkipped() {
        assertEquals("\u00A7a\uA731\u029C\u1D0F\u1D18", SmallCapsUtil.applySmallTag("\u00A7aShop"));
        assertEquals("\u00A7x\u00A7f\u00A7f\u00A79\u00A7b\u00A70\u00A70\u0262\u1D0F",
                SmallCapsUtil.applySmallTag("\u00A7x\u00A7f\u00A7f\u00A79\u00A7b\u00A70\u00A70go"));
    }

    @Test
    void miniMessageTagsSkipped() {
        assertEquals("<bold>\u1D0D\u1D07\u0274\u1D1C</bold>",
                SmallCapsUtil.applySmallTag("<bold>menu</bold>"));
    }

    @Test
    void literalAngleBracketStillTransforms() {
        assertEquals("\u026A<3", SmallCapsUtil.applySmallTag("i<3"));
    }

    @Test
    void outputLengthAlwaysEqualsInput() {
        String[] inputs = {
                "abcdefghijklmnopqrstuvwxyz",
                "&aShop &lMenu &#ff9b00gold",
                "<bold>menu</bold>",
                "Mixed LINE &a<i>tag</i> men\u00FA 123 !?"
        };
        for (String input : inputs) {
            assertEquals(input.length(), SmallCapsUtil.applySmallTag(input).length());
        }
    }

    @Test
    void unchangedLineReturnsSameInstance() {
        String noLetters = "123 &a<bold>!";
        assertSame(noLetters, SmallCapsUtil.applySmallTag(noLetters));
        assertSame(SMALL_ALPHABET, SmallCapsUtil.applySmallTag(SMALL_ALPHABET));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(SmallCapsUtil.applySmallTag(null));
        String empty = "";
        assertSame(empty, SmallCapsUtil.applySmallTag(empty));
    }

    @Test
    void tagIsCaseInsensitive() {
        assertEquals(SnText.applyPrefixTags("[small]ab"), SnText.applyPrefixTags("[SMALL]ab"));
    }

    @Test
    void smallAndRgbComposeInAnyOrder() {
        assertEquals(SnText.applyPrefixTags("[small][rgb]ab"), SnText.applyPrefixTags("[rgb][small]ab"));
    }

    @Test
    void centerMarkSurvivesSmall() {
        assertEquals("[center]\u029C\u026A", SnText.applyPrefixTags("[center][small]hi"));
    }
}
