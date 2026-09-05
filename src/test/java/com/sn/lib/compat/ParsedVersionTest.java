package com.sn.lib.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedVersionTest {

    private static void assertVersion(ParsedVersion parsed, int major, int minor, int patch) {
        assertEquals(major, parsed.major(), "major");
        assertEquals(minor, parsed.minor(), "minor");
        assertEquals(patch, parsed.patch(), "patch");
        assertTrue(parsed.parsed(), "parsed");
    }

    @Test
    void classicLineParsesAndGatesNumerically() {
        ParsedVersion v = ParsedVersion.parse("1.21.1-R0.1-SNAPSHOT");
        assertVersion(v, 1, 21, 1);
        assertFalse(v.yearScheme());
        assertTrue(v.atLeast(20, 6));
        assertTrue(v.atLeast(21, 0));
        assertTrue(v.atLeast(21, 1));
        assertFalse(v.atLeast(21, 2));
    }

    @Test
    void classicFloorStaysBelowNewerGates() {
        ParsedVersion v = ParsedVersion.parse("1.20.4-R0.1-SNAPSHOT");
        assertVersion(v, 1, 20, 4);
        assertTrue(v.atLeast(20, 4));
        assertFalse(v.atLeast(20, 6));
        assertFalse(v.atLeast(21, 0));
    }

    @Test
    void classicMinorWithoutPatchReadsPatchZero() {
        ParsedVersion v = ParsedVersion.parse("1.21-R0.1-SNAPSHOT");
        assertVersion(v, 1, 21, 0);
        assertTrue(v.atLeast(21, 0));
        assertFalse(v.atLeast(21, 1));
    }

    @Test
    void yearBasedDropIsRecognizedAndNewerThanEveryClassicGate() {
        ParsedVersion v = ParsedVersion.parse("26.2.build.2632-stable");
        assertVersion(v, 26, 2, 0);
        assertTrue(v.yearScheme());
        assertTrue(v.atLeast(20, 6));
        assertTrue(v.atLeast(21, 0));
        assertTrue(v.atLeast(21, 11));
    }

    @Test
    void yearBasedPatchKeepsItsPatchNumber() {
        ParsedVersion v = ParsedVersion.parse("26.1.2.build.63-stable");
        assertVersion(v, 26, 1, 2);
        assertTrue(v.yearScheme());
    }

    @Test
    void paperChannelSuffixDoesNotMatter() {
        ParsedVersion v = ParsedVersion.parse("26.1.2.build.23-alpha");
        assertVersion(v, 26, 1, 2);
        assertTrue(v.yearScheme());
    }

    @Test
    void unparseableStringFallsBackToClassicTargetWithFullSupport() {
        ParsedVersion v = ParsedVersion.parse("garbage");
        assertFalse(v.parsed());
        assertEquals(1, v.major());
        assertEquals(21, v.minor());
        assertEquals(8, v.patch());
        assertFalse(v.yearScheme());
        assertTrue(v.atLeast(21, 8));
        assertTrue(v.atLeast(99, 99));
    }

    @Test
    void nullStringIsTreatedAsUnparseable() {
        ParsedVersion v = ParsedVersion.parse(null);
        assertFalse(v.parsed());
        assertTrue(v.atLeast(21, 0));
    }
}
