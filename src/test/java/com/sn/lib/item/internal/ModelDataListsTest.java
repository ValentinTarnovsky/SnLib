package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse rules of the {@code custom-model-data-floats/flags/strings/colors} yml keys
 * (1.46.0). The reflective write needs a 1.21.4+ server, so only the server-free part is
 * driven here: the value formats, the skipped bad entries and the "nothing declared" rule.
 */
class ModelDataListsTest {

    @Test
    void floatsParseIntsDecimalsAndNegatives() {
        List<String> bad = new ArrayList<>();
        assertEquals(List.of(1.0f, 0.5f, -2.25f, 100000f),
                ModelDataLists.parseFloats(List.of("1", "0.5", " -2.25 ", "100000"), bad::add));
        assertTrue(bad.isEmpty());
    }

    @Test
    void aBadFloatIsSkippedAndReported() {
        List<String> bad = new ArrayList<>();
        assertEquals(List.of(1.0f, 3.0f),
                ModelDataLists.parseFloats(List.of("1", "abc", "NaN", "Infinity", "", "3"),
                        bad::add));
        assertEquals(List.of("abc", "NaN", "Infinity", ""), bad);
    }

    @Test
    void flagsParseTrueAndFalseInAnyCase() {
        List<String> bad = new ArrayList<>();
        assertEquals(List.of(true, false, true),
                ModelDataLists.parseFlags(List.of("true", "FALSE", " True "), bad::add));
        assertTrue(bad.isEmpty());
    }

    @Test
    void aBadFlagIsSkippedAndReported() {
        List<String> bad = new ArrayList<>();
        assertEquals(List.of(false),
                ModelDataLists.parseFlags(List.of("yes", "1", "false"), bad::add));
        assertEquals(List.of("yes", "1"), bad);
    }

    @Test
    void colorsAcceptHashHexBareHexAndRgbTriples() {
        List<String> bad = new ArrayList<>();
        List<Color> colors = ModelDataLists.parseColors(
                List.of("#2B6CB0", "ff5500", "0, 128, 255", " 10,20,30 "), bad::add);
        assertTrue(bad.isEmpty());
        assertEquals(List.of(Color.fromRGB(0x2B6CB0), Color.fromRGB(0xFF5500),
                Color.fromRGB(0, 128, 255), Color.fromRGB(10, 20, 30)), colors);
    }

    @Test
    void aBadColorIsSkippedAndTheRestApplies() {
        List<String> bad = new ArrayList<>();
        List<Color> colors = ModelDataLists.parseColors(
                List.of("#12345", "GGGGGG", "256, 0, 0", "1, 2", "red", "#00FF00"), bad::add);
        assertEquals(List.of(Color.fromRGB(0x00FF00)), colors);
        assertEquals(List.of("#12345", "GGGGGG", "256, 0, 0", "1, 2", "red"), bad);
    }

    @Test
    void theSharedColorParserRejectsMalformedValues() {
        assertEquals(Color.fromRGB(255, 85, 85), ModelDataLists.parseColor("255, 85, 85"));
        assertEquals(Color.fromRGB(0xFF5555), ModelDataLists.parseColor("#FF5555"));
        assertNull(ModelDataLists.parseColor(""));
        assertNull(ModelDataLists.parseColor("#"));
        assertNull(ModelDataLists.parseColor("-1, 0, 0"));
    }

    @Test
    void anEmptyDeclaredListStaysAnEmptyList() {
        // Declaring "[]" is an explicit "clear that list", distinct from the key being absent.
        assertEquals(List.of(), ModelDataLists.parseFloats(List.of(), bad -> { }));
        assertEquals(List.of(), ModelDataLists.parseFlags(List.of(), bad -> { }));
        assertEquals(List.of(), ModelDataLists.parseColors(List.of(), bad -> { }));
    }

    @Test
    void noneDeclaresNothingAndApplyingNothingTouchesNothing() {
        assertTrue(ModelDataLists.NONE.isEmpty());
        assertFalse(new ModelDataLists(null, List.of(true), null, null).isEmpty());
        assertFalse(new ModelDataLists(null, null, null, List.of()).isEmpty());
        // All lists null: false before any meta access or reflective lookup.
        assertFalse(ModelDataLists.apply(null, null, null, null, null));
    }

    @Test
    void copyDropsNullElementsAndKeepsNullAsNotSet() {
        assertNull(ModelDataLists.copy(null));
        List<String> source = new ArrayList<>(Arrays.asList("a", null, "b"));
        List<String> copy = ModelDataLists.copy(source);
        assertEquals(List.of("a", "b"), copy);
        source.clear();
        assertEquals(List.of("a", "b"), copy);
    }
}
