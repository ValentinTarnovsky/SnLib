package com.sn.lib.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Parses inventory slot definitions from YML values into slot indexes.
 *
 * <p>Accepts a single int, a numeric string, a range ({@code "0-8"}), a
 * comma-separated mix ({@code "0,2,4-6"}) or a list combining any of those.
 * Invalid input yields an empty array plus a delegable WARN: the caller
 * supplies the warning sink, so this class stays pure (no Bukkit).</p>
 */
public final class SlotParser {

    /** Upper bound on a single range span; larger ranges are rejected with a WARN. */
    private static final int MAX_RANGE_SPAN = 10_000;

    private SlotParser() {
    }

    /** Parses {@code raw} discarding warnings. */
    public static int[] parse(Object raw) {
        return parse(raw, null);
    }

    /**
     * Parses {@code raw} into distinct slot indexes in first-seen order.
     *
     * @param warn sink for warnings about invalid tokens; may be null
     * @return slot indexes; empty when nothing valid was found
     */
    public static int[] parse(Object raw, Consumer<String> warn) {
        Set<Integer> out = new LinkedHashSet<>();
        collect(raw, out, warn);
        int[] slots = new int[out.size()];
        int i = 0;
        for (int slot : out) {
            slots[i++] = slot;
        }
        return slots;
    }

    private static void collect(Object raw, Set<Integer> out, Consumer<String> warn) {
        if (raw == null) {
            warn(warn, "Slot definition is null");
            return;
        }
        if (raw instanceof Number number) {
            addSlot(Math.toIntExact(number.longValue()), out, warn);
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collect(element, out, warn);
            }
            return;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            warn(warn, "Slot definition is empty");
            return;
        }
        for (String token : text.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parseToken(trimmed, out, warn);
        }
    }

    private static void parseToken(String token, Set<Integer> out, Consumer<String> warn) {
        int dash = token.indexOf('-', 1);
        if (dash < 0) {
            Integer single = parseInt(token);
            if (single == null) {
                warn(warn, "Invalid slot token '" + token + "'");
                return;
            }
            addSlot(single, out, warn);
            return;
        }
        Integer from = parseInt(token.substring(0, dash).trim());
        Integer to = parseInt(token.substring(dash + 1).trim());
        if (from == null || to == null) {
            warn(warn, "Invalid slot range '" + token + "'");
            return;
        }
        int low = Math.min(from, to);
        int high = Math.max(from, to);
        if (high - low > MAX_RANGE_SPAN) {
            warn(warn, "Slot range '" + token + "' exceeds " + MAX_RANGE_SPAN + " slots; ignored");
            return;
        }
        for (int slot = low; slot <= high; slot++) {
            addSlot(slot, out, warn);
        }
    }

    private static void addSlot(int slot, Set<Integer> out, Consumer<String> warn) {
        if (slot < 0) {
            warn(warn, "Negative slot " + slot + " ignored");
            return;
        }
        out.add(slot);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void warn(Consumer<String> warn, String message) {
        if (warn != null) {
            warn.accept(message);
        }
    }
}
