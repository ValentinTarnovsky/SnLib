package com.sn.lib.item.internal;

import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decision-logic units for {@link SkinCache}: freshness/expiry of the positive cache,
 * the short negative window, the in-flight gate and the fetch predicate, all driven by an
 * injected clock with no live server.
 */
class SkinCacheTest {

    private final long[] now = {1_000L};
    private final LongSupplier clock = () -> now[0];

    private SkinCache<String> cache(long positiveTtl, long negativeTtl, int maxSize) {
        return new SkinCache<>(positiveTtl, negativeTtl, maxSize, clock);
    }

    @Test
    void freshValueIsReturnedBeforeExpiry() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.put("a", "skin");
        now[0] += 99;
        assertEquals("skin", cache.get("a"));
    }

    @Test
    void valueExpiresAtTtlBoundary() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.put("a", "skin");
        now[0] += 100;
        assertNull(cache.get("a"), "an entry is stale once the clock reaches its expiry");
        assertEquals(0, cache.positiveSize(), "the stale entry is evicted on the miss");
    }

    @Test
    void sizeBoundEvictsEldest() {
        SkinCache<String> cache = cache(10_000, 10_000, 3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4");
        assertEquals(3, cache.positiveSize(), "the cap is never exceeded");
        assertNull(cache.get("a"), "the eldest entry is evicted");
        assertEquals("4", cache.get("d"));
    }

    @Test
    void negativeHitWithinWindowThenExpires() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.putNegative("ghost");
        assertTrue(cache.negativeHit("ghost"));
        now[0] += 50;
        assertFalse(cache.negativeHit("ghost"), "the negative entry clears at its TTL");
    }

    @Test
    void positivePutClearsPriorNegative() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.putNegative("late");
        cache.put("late", "skin");
        assertFalse(cache.negativeHit("late"), "a successful resolve cancels the negative window");
        assertEquals("skin", cache.get("late"));
    }

    @Test
    void shouldFetchWhenCompletelyUnknown() {
        SkinCache<String> cache = cache(100, 50, 8);
        assertTrue(cache.shouldFetch("x"));
    }

    @Test
    void shouldNotFetchWhenCached() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.put("x", "skin");
        assertFalse(cache.shouldFetch("x"));
    }

    @Test
    void shouldNotFetchWhenNegativelyCached() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.putNegative("x");
        assertFalse(cache.shouldFetch("x"));
    }

    @Test
    void shouldFetchAgainAfterNegativeExpires() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.putNegative("x");
        now[0] += 50;
        assertTrue(cache.shouldFetch("x"), "an expired negative no longer suppresses the fetch");
    }

    @Test
    void beginFetchIsAtomicAndSingleFlighted() {
        SkinCache<String> cache = cache(100, 50, 8);
        assertTrue(cache.beginFetch("x"), "the first caller owns the fetch");
        assertTrue(cache.inFlight("x"));
        assertFalse(cache.beginFetch("x"), "a concurrent caller is refused while in flight");
        assertFalse(cache.shouldFetch("x"), "the predicate agrees a fetch is already running");
    }

    @Test
    void endFetchReopensTheGate() {
        SkinCache<String> cache = cache(100, 50, 8);
        assertTrue(cache.beginFetch("x"));
        cache.endFetch("x");
        assertFalse(cache.inFlight("x"));
        assertTrue(cache.beginFetch("x"), "the key can be fetched again once released");
    }

    @Test
    void beginFetchRefusedWhenAlreadyCached() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.put("x", "skin");
        assertFalse(cache.beginFetch("x"));
        assertFalse(cache.inFlight("x"), "a refused begin never marks the key in flight");
    }

    @Test
    void clearResetsPositiveNegativeAndInFlight() {
        SkinCache<String> cache = cache(100, 50, 8);
        cache.put("a", "1");
        cache.putNegative("b");
        cache.beginFetch("c");
        cache.clear();
        assertEquals(0, cache.positiveSize());
        assertNull(cache.get("a"));
        assertFalse(cache.negativeHit("b"));
        assertFalse(cache.inFlight("c"));
        assertTrue(cache.shouldFetch("c"));
    }
}
