package com.sn.lib.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable weighted random picker (pure, no Bukkit).
 *
 * <p>Backed by a cumulative-weight {@link TreeMap}: {@link #pick(Random)} draws
 * {@code r} uniformly in {@code [0, totalWeight)} and resolves the entry in
 * O(log n) via {@code ceilingEntry(r)}. Weights stay {@code double} end to end;
 * they are never truncated to int, so fractional weights keep their exact
 * relative probabilities. Build instances with {@link #builder()}.</p>
 *
 * @param <T> pooled value type
 */
public final class WeightedRandomPool<T> {

    private final NavigableMap<Double, T> cumulative;
    private final double totalWeight;

    private WeightedRandomPool(NavigableMap<Double, T> cumulative, double totalWeight) {
        this.cumulative = cumulative;
        this.totalWeight = totalWeight;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Weighted random pick using the given source of randomness.
     *
     * @throws NoSuchElementException when the pool is empty
     */
    public T pick(Random random) {
        if (cumulative.isEmpty()) {
            throw new NoSuchElementException("WeightedRandomPool is empty");
        }
        double r = random.nextDouble() * totalWeight;
        Map.Entry<Double, T> entry = cumulative.ceilingEntry(r);
        return entry != null ? entry.getValue() : cumulative.lastEntry().getValue();
    }

    /** Weighted random pick using {@link ThreadLocalRandom}. */
    public T pick() {
        return pick(ThreadLocalRandom.current());
    }

    public int size() {
        return cumulative.size();
    }

    public boolean isEmpty() {
        return cumulative.isEmpty();
    }

    public double totalWeight() {
        return totalWeight;
    }

    /** Pooled values in cumulative-weight order (unmodifiable). */
    public Collection<T> values() {
        return Collections.unmodifiableCollection(cumulative.values());
    }

    /**
     * Accumulates weighted entries; entries with non-positive or non-finite
     * weight are ignored, matching the RandomCollection contract.
     */
    public static final class Builder<T> {

        private final NavigableMap<Double, T> cumulative = new TreeMap<>();
        private double totalWeight;

        private Builder() {
        }

        public Builder<T> add(T value, double weight) {
            if (weight > 0D && Double.isFinite(weight)) {
                totalWeight += weight;
                cumulative.put(totalWeight, value);
            }
            return this;
        }

        public WeightedRandomPool<T> build() {
            return new WeightedRandomPool<>(new TreeMap<>(cumulative), totalWeight);
        }
    }
}
