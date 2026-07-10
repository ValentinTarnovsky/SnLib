package com.sn.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.sn.lib.text.RgbGradientUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbGradientTest {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-F]{6})");
    private static final String FIRST_ANCHOR = "F300F3";
    private static final String LAST_ANCHOR = "FF5327";

    private static List<String> hexCodes(String s) {
        List<String> out = new ArrayList<>();
        Matcher matcher = HEX.matcher(s);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    @Test
    void emitsOneHexPerVisibleCharacter() {
        assertEquals(5, hexCodes(RgbGradientUtil.applyRgbTag("Hello")).size());
        assertEquals(8, hexCodes(RgbGradientUtil.applyRgbTag("Gradient")).size());
    }

    @Test
    void extremesUseExactAnchors() {
        List<String> codes = hexCodes(RgbGradientUtil.applyRgbTag("Gradient"));
        assertEquals(FIRST_ANCHOR, codes.get(0));
        assertEquals(LAST_ANCHOR, codes.get(codes.size() - 1));
    }

    @Test
    void spacesDoNotConsumeGradientPositions() {
        String spaced = RgbGradientUtil.applyRgbTag("A B");
        assertEquals("&#" + FIRST_ANCHOR + "A &#" + LAST_ANCHOR + "B", spaced);
        assertEquals(hexCodes(RgbGradientUtil.applyRgbTag("AB")), hexCodes(spaced));
    }

    @Test
    void formatPreservedAndColorOverridden() {
        String out = RgbGradientUtil.applyRgbTag("&a&lAB");
        assertEquals("&#" + FIRST_ANCHOR + "&lA&#" + LAST_ANCHOR + "&lB", out);
        assertFalse(out.toLowerCase().contains("&a"));
        assertTrue(out.contains("&l"));
    }

    @Test
    void resetClearsAccumulatedFormat() {
        String out = RgbGradientUtil.applyRgbTag("&lA&rB");
        assertEquals("&#" + FIRST_ANCHOR + "&lA&#" + LAST_ANCHOR + "B", out);
    }

    @Test
    void existingHexColorIsDiscarded() {
        String out = RgbGradientUtil.applyRgbTag("&#123456AB");
        assertEquals("&#" + FIRST_ANCHOR + "A&#" + LAST_ANCHOR + "B", out);
    }

    @Test
    void singleVisibleCharacterGetsFirstAnchor() {
        assertEquals("&#" + FIRST_ANCHOR + "X", RgbGradientUtil.applyRgbTag("X"));
    }
}
