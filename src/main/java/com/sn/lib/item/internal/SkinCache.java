package com.sn.lib.item.internal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

/**
 * Bounded, TTL-aware resolution cache backing {@link SkinResolver}: a positive cache of
 * resolved values (access-order LRU, size-bounded, per-entry expiry), a short negative
 * cache of failed keys (so a genuinely unresolvable owner is not refetched on every
 * render) and an in-flight guard that collapses concurrent fetches of the same key into
 * one.
 *
 * <p>Generic and Bukkit-free ON PURPOSE: every decision (fresh hit, expiry, negative
 * suppression, in-flight dedupe, eviction) is a pure unit exercised with an injected
 * clock, independent of any live server. The Bukkit-facing {@link SkinResolver}
 * instantiates it with {@code PlayerProfile} values.</p>
 *
 * <p>Thread-safety: every method synchronizes on the instance. Server-wide single
 * instance justified by the resolver: skin data is content-addressed and identical for
 * every consumer (contract note (a) of {@code SnLib}).</p>
 */
public final class SkinCache<V> {

    private final long positiveTtlMillis;
    private final long negativeTtlMillis;
    private final int maxSize;
    private final LongSupplier clock;

    private final Map<String, Entry<V>> positive;
    private final Map<String, Long> negative;
    private final Set<String> inFlight = new HashSet<>();

    /** One positive-cache value with its absolute expiry instant in clock millis. */
    private record Entry<V>(V value, long expiresAt) {
    }

    /**
     * @param positiveTtlMillis how long a resolved value stays fresh
     * @param negativeTtlMillis how long a failed key is suppressed from refetching
     * @param maxSize           upper bound of either cache (eldest entries evicted); floored to 1
     * @param clock             monotonic-enough millis source; injectable for tests
     */
    public SkinCache(long positiveTtlMillis, long negativeTtlMillis, int maxSize, LongSupplier clock) {
        this.positiveTtlMillis = positiveTtlMillis;
        this.negativeTtlMillis = negativeTtlMillis;
        this.maxSize = Math.max(1, maxSize);
        this.clock = clock;
        this.positive = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
                return size() > SkinCache.this.maxSize;
            }
        };
        this.negative = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > SkinCache.this.maxSize;
            }
        };
    }

    /** Fresh value for {@code key}, or null on a miss; an expired entry is dropped and misses. */
    public synchronized @Nullable V get(String key) {
        Entry<V> entry = positive.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.getAsLong() >= entry.expiresAt()) {
            positive.remove(key);
            return null;
        }
        return entry.value();
    }

    /** Stores a resolved value with a fresh positive TTL and clears any prior failure. */
    public synchronized void put(String key, V value) {
        positive.put(key, new Entry<>(value, clock.getAsLong() + positiveTtlMillis));
        negative.remove(key);
    }

    /** True while {@code key} sits inside the negative TTL window; an expired entry is dropped. */
    public synchronized boolean negativeHit(String key) {
        Long expiresAt = negative.get(key);
        if (expiresAt == null) {
            return false;
        }
        if (clock.getAsLong() >= expiresAt) {
            negative.remove(key);
            return false;
        }
        return true;
    }

    /** Marks {@code key} as recently failed for the negative TTL. */
    public synchronized void putNegative(String key) {
        negative.put(key, clock.getAsLong() + negativeTtlMillis);
    }

    /**
     * Pure decision predicate: true iff a fetch is warranted - no fresh positive hit, no
     * live negative suppression and no fetch already in flight. Read-only apart from the
     * lazy eviction of expired entries done by {@link #get} and {@link #negativeHit}.
     */
    public synchronized boolean shouldFetch(String key) {
        return get(key) == null && !negativeHit(key) && !inFlight.contains(key);
    }

    /**
     * Atomic fetch gate: if {@link #shouldFetch} holds, marks {@code key} in-flight and
     * returns true (the caller owns the fetch); otherwise returns false. Pair every true
     * result with a later {@link #endFetch}.
     */
    public synchronized boolean beginFetch(String key) {
        if (!shouldFetch(key)) {
            return false;
        }
        inFlight.add(key);
        return true;
    }

    /** Releases the in-flight mark of {@code key}. */
    public synchronized void endFetch(String key) {
        inFlight.remove(key);
    }

    /** Empties both caches and the in-flight set. */
    public synchronized void clear() {
        positive.clear();
        negative.clear();
        inFlight.clear();
    }

    /** Live positive-cache size; test visibility. */
    synchronized int positiveSize() {
        return positive.size();
    }

    /** True while {@code key} is marked in-flight; test visibility. */
    synchronized boolean inFlight(String key) {
        return inFlight.contains(key);
    }
}
