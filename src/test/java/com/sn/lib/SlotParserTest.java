package com.sn.lib;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sn.lib.util.SlotParser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotParserTest {

    @Test
    void parsesSingleInt() {
        assertArrayEquals(new int[] {5}, SlotParser.parse(5));
        assertArrayEquals(new int[] {0}, SlotParser.parse(0));
    }

    @Test
    void parsesNumericString() {
        assertArrayEquals(new int[] {7}, SlotParser.parse("7"));
        assertArrayEquals(new int[] {13}, SlotParser.parse(" 13 "));
    }

    @Test
    void parsesRange() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}, SlotParser.parse("0-8"));
        assertArrayEquals(new int[] {10, 11, 12}, SlotParser.parse("10 - 12"));
    }

    @Test
    void normalizesReversedRange() {
        assertArrayEquals(new int[] {0, 1, 2}, SlotParser.parse("2-0"));
    }

    @Test
    void parsesCommaSeparatedMix() {
        assertArrayEquals(new int[] {0, 2, 4, 5, 6}, SlotParser.parse("0,2,4-6"));
    }

    @Test
    void parsesListOfMixedElements() {
        assertArrayEquals(new int[] {1, 3, 4, 5, 7}, SlotParser.parse(List.of(1, "3-5", "7")));
    }

    @Test
    void deduplicatesKeepingFirstSeenOrder() {
        assertArrayEquals(new int[] {4, 5, 6, 3}, SlotParser.parse("4-6,5,3"));
        assertArrayEquals(new int[] {1, 2}, SlotParser.parse("1,1,1-2"));
    }

    @Test
    void invalidInputYieldsEmptyAndDelegatesWarn() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[0], SlotParser.parse("abc", warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("abc"));
    }

    @Test
    void nullYieldsEmptyAndDelegatesWarn() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[0], SlotParser.parse(null, warnings::add));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void negativeSlotsAreWarnedAndSkipped() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[0], SlotParser.parse(-1, warnings::add));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void invalidTokensAreSkippedButValidOnesKept() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[] {1, 2}, SlotParser.parse("1,x,2", warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void oversizedRangeIsRejected() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[0], SlotParser.parse("0-999999999", warnings::add));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void nullWarnConsumerIsSafe() {
        assertArrayEquals(new int[0], SlotParser.parse("garbage"));
    }
}
