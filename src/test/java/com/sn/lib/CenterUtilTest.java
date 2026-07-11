package com.sn.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.sn.lib.text.CenterUtil;
import com.sn.lib.text.SnText;

/**
 * Pixel-exact centering against the 154px half-width: color codes are invisible while
 * measuring, bold widens glyphs, and lines already wider than the window pass through.
 */
class CenterUtilTest {

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    @Test
    void centersShortLineWithExactPixelMath() {
        // 'a' and 'b' measure 5+1 px each -> px=12; compensate 154-6=148px in 4px spaces.
        String centered = CenterUtil.center("ab");
        assertEquals(" ".repeat(37) + "ab", centered);
    }

    @Test
    void emptyAndNullPassThrough() {
        assertEquals("", CenterUtil.center(""));
        assertSame(null, CenterUtil.center(null));
    }

    @Test
    void lineWiderThanWindowIsUnchanged() {
        String wide = "W".repeat(60);
        assertSame(wide, CenterUtil.center(wide));
    }

    @Test
    void colorCodesAreIgnoredWhileMeasuring() {
        String plain = CenterUtil.center("Shop Menu");
        String legacy = CenterUtil.center("&aShop &eMenu");
        String hex = CenterUtil.center("&#55FF55Shop &#FCFF21Menu");
        assertEquals(leadingSpaces(plain), leadingSpaces(legacy));
        assertEquals(leadingSpaces(plain), leadingSpaces(hex));
    }

    @Test
    void sectionSignCodesAreIgnoredWhileMeasuring() {
        String plain = CenterUtil.center("Shop Menu");
        String section = CenterUtil.center("§aShop §eMenu");
        assertEquals(leadingSpaces(plain), leadingSpaces(section));
    }

    @Test
    void boldWidensTheMeasuredLine() {
        // Bold adds 1px per non-space glyph, so a long bold line needs fewer lead spaces.
        String plain = CenterUtil.center("Gradient Title Line");
        String bold = CenterUtil.center("&lGradient Title Line");
        assertTrue(leadingSpaces(bold) < leadingSpaces(plain));
    }

    @Test
    void resetStopsBoldMeasurement() {
        String boldThenReset = CenterUtil.center("&lab&rcdefgh");
        String boldAll = CenterUtil.center("&labcdefgh");
        assertTrue(leadingSpaces(boldThenReset) >= leadingSpaces(boldAll));
    }

    @Test
    void smallCapsLineMeasuresLikeUppercase() {
        // Small caps glyphs measure base 5 like uppercase; U+026A measures like 'I' (base 3).
        assertEquals(leadingSpaces(CenterUtil.center("HELLO")),
                leadingSpaces(CenterUtil.center(SnText.smallCaps("HELLO"))));
        assertEquals(leadingSpaces(CenterUtil.center("HI")),
                leadingSpaces(CenterUtil.center(SnText.smallCaps("HI"))));
    }

    @Test
    void centeredGradientLineKeepsPayloadIntact() {
        // A line as it leaves the [rgb] phase: one hex code per visible character.
        String gradient = "&#F300F3H&#5555FFi&#55FFFF!";
        String centered = CenterUtil.center(gradient);
        assertTrue(centered.endsWith(gradient));
        // Only the three visible glyphs count: H(6) + i(2) + !(2) = 10px -> 149/4 -> 38 spaces.
        assertEquals(38, leadingSpaces(centered));
    }
}
