package com.sn.lib;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sn.lib.hook.SemverComparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemverComparatorTest {

    private static void assertOlder(String left, String right) {
        assertTrue(SemverComparator.compareVersions(left, right) < 0,
                left + " should be older than " + right);
        assertTrue(SemverComparator.compareVersions(right, left) > 0,
                right + " should be newer than " + left);
    }

    private static void assertSame(String left, String right) {
        assertEquals(0, SemverComparator.compareVersions(left, right),
                left + " should equal " + right);
    }

    @Test
    void comparesSegmentsNumericallyNotLexically() {
        assertOlder("1.9", "1.10");
        assertOlder("1.9.9", "1.10.0");
        assertOlder("1.99.9", "1.100.0");
        assertOlder("2.9", "2.11");
    }

    @Test
    void supportsSegmentsOfAnyDigitCount() {
        assertOlder("1.2.3", "1.2.30");
        assertOlder("9.0.0", "10.0.0");
        assertOlder("1.2.345", "1.2.1000");
    }

    @Test
    void missingTrailingSegmentsCountAsZero() {
        assertSame("1.2", "1.2.0");
        assertSame("1", "1.0.0");
        assertOlder("1.2", "1.2.1");
        assertOlder("1.2", "1.2.0.1");
    }

    @Test
    void snapshotSuffixIsIgnored() {
        assertSame("1.0.0-SNAPSHOT", "1.0.0");
        assertSame("2.11.6-DEV-SNAPSHOT", "2.11.6");
        assertOlder("1.9-SNAPSHOT", "2.0-SNAPSHOT");
        assertOlder("1.0.0-SNAPSHOT", "1.0.1");
    }

    @Test
    void equalVersionsCompareAsZero() {
        assertSame("1.2.3", "1.2.3");
        assertSame("0.0.0", "0");
    }

    @Test
    void comparatorInstanceSortsAscending() {
        List<String> versions = new ArrayList<>(List.of("1.10.0", "1.2", "1.9.9", "2.0.0-SNAPSHOT", "1.2.1"));
        versions.sort(new SemverComparator());
        assertEquals(List.of("1.2", "1.2.1", "1.9.9", "1.10.0", "2.0.0-SNAPSHOT"), versions);
    }
}
