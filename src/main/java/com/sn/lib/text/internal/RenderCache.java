package com.sn.lib.text.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.kyori.adventure.text.Component;

/**
 * Process-wide memo of {@code SnText.color} results, keyed by the raw input line.
 *
 * <p>The full text pipeline is a pure function of its input and Adventure components are
 * immutable, so one rendered component can be shared by every caller on every thread. The
 * hot caller is a menu with {@code update-interval:}: each tick rebuilds every display-name
 * and lore line of every item for every viewer, and almost all of those lines are identical
 * to the previous tick. Re-running MiniMessage on them was pure allocation churn (spark on
 * 1.40.1: ~7% of the server thread under {@code GuiSession.menuTick}).</p>
 *
 * <p>Bounded two ways: lines longer than {@link #MAX_KEY_LENGTH} are rendered and never
 * stored, and reaching {@link #MAX_ENTRIES} clears the whole map before the next insert, so
 * content that changes every tick (a live countdown) churns keys without growing memory. A
 * miss renders outside any lock: two threads racing on the same new line both render it
 * and the last put wins, which is harmless for a pure function.</p>
 */
public final class RenderCache {

    static final int MAX_ENTRIES = 2048;
    static final int MAX_KEY_LENGTH = 256;

    private static final Map<String, Component> CACHE = new ConcurrentHashMap<>();

    private RenderCache() {
    }

    /** Cached render of {@code raw}, computing it through {@code render} on a miss. */
    public static Component get(String raw, Function<String, Component> render) {
        Component cached = CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        Component rendered = render.apply(raw);
        if (raw.length() <= MAX_KEY_LENGTH) {
            if (CACHE.size() >= MAX_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(raw, rendered);
        }
        return rendered;
    }

    /** Drops every cached component; runs on the SnLib plugin teardown. */
    public static void clear() {
        CACHE.clear();
    }

    static int size() {
        return CACHE.size();
    }
}
