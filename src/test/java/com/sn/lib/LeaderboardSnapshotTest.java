package com.sn.lib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sn.lib.leaderboard.LeaderboardCache.Entry;
import com.sn.lib.leaderboard.LeaderboardCache.Snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardSnapshotTest {

    private static UUID uuid(int n) {
        return UUID.nameUUIDFromBytes(("player-" + n).getBytes());
    }

    private static List<String> names(List<Entry> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            out.add(entry.name());
        }
        return out;
    }

    @Test
    void ordersByValueDescending() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Ana", 10.0),
                new Entry(uuid(2), "Beto", 300.0),
                new Entry(uuid(3), "Caro", 25.5)));
        assertEquals(List.of("Beto", "Caro", "Ana"), names(snapshot.top(3)));
    }

    @Test
    void tiesBreakByNameAscending() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Zoe", 50.0),
                new Entry(uuid(2), "Ana", 50.0),
                new Entry(uuid(3), "Mia", 50.0),
                new Entry(uuid(4), "Top", 99.0)));
        assertEquals(List.of("Top", "Ana", "Mia", "Zoe"), names(snapshot.top(4)));
    }

    @Test
    void topClampsToSizeAndRejectsNonPositive() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Ana", 1.0),
                new Entry(uuid(2), "Beto", 2.0)));
        assertEquals(2, snapshot.top(10).size());
        assertEquals(1, snapshot.top(1).size());
        assertEquals("Beto", snapshot.top(1).get(0).name());
        assertTrue(snapshot.top(0).isEmpty());
        assertTrue(snapshot.top(-3).isEmpty());
    }

    @Test
    void positionsAreOneBasedAndZeroWhenUnranked() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Ana", 10.0),
                new Entry(uuid(2), "Beto", 300.0),
                new Entry(uuid(3), "Caro", 25.5)));
        assertEquals(1, snapshot.positionOf(uuid(2)));
        assertEquals(2, snapshot.positionOf(uuid(3)));
        assertEquals(3, snapshot.positionOf(uuid(1)));
        assertEquals(0, snapshot.positionOf(uuid(99)));
        assertEquals(0, snapshot.positionOf(null));
    }

    @Test
    void valueOfReturnsCachedValueAndZeroWhenUnranked() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Ana", 10.0),
                new Entry(uuid(2), "Beto", 300.0)));
        assertEquals(300.0, snapshot.valueOf(uuid(2)));
        assertEquals(10.0, snapshot.valueOf(uuid(1)));
        assertEquals(0.0, snapshot.valueOf(uuid(99)));
        assertEquals(0.0, snapshot.valueOf(null));
    }

    @Test
    void duplicateUuidKeepsBestPosition() {
        UUID repeated = uuid(7);
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(repeated, "Alt", 5.0),
                new Entry(repeated, "Main", 100.0),
                new Entry(uuid(1), "Otro", 50.0)));
        assertEquals(1, snapshot.positionOf(repeated));
        assertEquals(100.0, snapshot.valueOf(repeated));
    }

    @Test
    void snapshotIsImmutable() {
        Snapshot snapshot = Snapshot.of(List.of(
                new Entry(uuid(1), "Ana", 1.0),
                new Entry(uuid(2), "Beto", 2.0)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.top(2).add(new Entry(uuid(3), "Caro", 3.0)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.top(1).add(new Entry(uuid(3), "Caro", 3.0)));
    }

    @Test
    void emptyAndNullInputsYieldEmptySnapshot() {
        assertEquals(0, Snapshot.of(List.of()).size());
        assertEquals(0, Snapshot.of(null).size());
        assertTrue(Snapshot.empty().top(5).isEmpty());
        assertEquals(0, Snapshot.empty().positionOf(uuid(1)));
    }

    @Test
    void nullRowsAndNullNamesAreTolerated() {
        Snapshot snapshot = Snapshot.of(Arrays.asList(
                new Entry(uuid(1), null, 10.0),
                null,
                new Entry(uuid(2), "Beto", 5.0)));
        assertEquals(2, snapshot.size());
        assertEquals("", snapshot.top(1).get(0).name());
        assertEquals(1, snapshot.positionOf(uuid(1)));
        assertEquals(2, snapshot.positionOf(uuid(2)));
    }
}
