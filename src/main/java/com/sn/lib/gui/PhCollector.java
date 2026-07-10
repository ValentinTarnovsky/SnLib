package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import com.sn.lib.Ph;

/**
 * Accumulator of local placeholder pairs handed to the {@code bindPaged} mapper: the
 * mapper fills one collector per paged entry and the session renders the template with
 * the collected pairs.
 */
public final class PhCollector {

    private final List<Ph> phs = new ArrayList<>();

    /** Adds a pair via {@link Ph#of}; null or empty keys are ignored. */
    public PhCollector add(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            phs.add(Ph.of(key, value));
        }
        return this;
    }

    /** Collected pairs in insertion order. */
    public Ph[] toArray() {
        return phs.toArray(new Ph[0]);
    }
}
