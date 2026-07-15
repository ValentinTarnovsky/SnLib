package com.sn.lib.command.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Pure alias reconciliation helpers for {@link BukkitCommandRegistry}: choosing the
 * authoritative alias source and diffing the currently-registered set against the desired
 * one. Kept free of Bukkit types so the whole decision layer is unit-testable without a
 * running server.
 *
 * <p>Source policy: a non-null {@code supplied} collection is AUTHORITATIVE - an empty list
 * therefore means "no aliases"; a null {@code supplied} means the source has no opinion and
 * the {@code fallback} (builder / plugin.yml aliases) applies instead. Every resolved alias
 * is trimmed, lowercased, de-duplicated in encounter order, and stripped of the root name
 * and of the plugin.yml declared aliases (those are owned by Bukkit, not by this dynamic
 * layer).</p>
 */
final class AliasReconciler {

    private AliasReconciler() {
    }

    /** Added and removed base alias keys between the active set and the desired set. */
    record Diff(List<String> added, List<String> removed) {
    }

    /**
     * Resolves the desired dynamic alias base keys. A non-null {@code supplied} wins over
     * {@code fallback}; the result excludes the root name and every plugin.yml declared
     * alias, and is lowercased and de-duplicated in encounter order.
     */
    static List<String> resolve(@Nullable Collection<String> supplied,
            Collection<String> fallback, String rootName, Collection<String> declaredAliases) {
        Collection<String> source = supplied != null ? supplied : fallback;
        String name = rootName == null ? "" : rootName.trim().toLowerCase(Locale.ROOT);
        Set<String> declared = lower(declaredAliases);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (source != null) {
            for (String raw : source) {
                if (raw == null) {
                    continue;
                }
                String alias = raw.trim().toLowerCase(Locale.ROOT);
                if (alias.isEmpty() || alias.equals(name) || declared.contains(alias)) {
                    continue;
                }
                out.add(alias);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Diffs the currently-registered {@code active} keys against the {@code desired} ones:
     * {@code added} are in desired but not active, {@code removed} are in active but not
     * desired. Both inputs are treated as lowercased base keys, compared case-insensitively.
     */
    static Diff diff(Collection<String> active, Collection<String> desired) {
        Set<String> current = lower(active);
        Set<String> target = lower(desired);
        List<String> added = new ArrayList<>();
        for (String alias : target) {
            if (!current.contains(alias)) {
                added.add(alias);
            }
        }
        List<String> removed = new ArrayList<>();
        for (String alias : current) {
            if (!target.contains(alias)) {
                removed.add(alias);
            }
        }
        return new Diff(added, removed);
    }

    /** Lowercased, trimmed, non-blank keys of the collection in encounter order. */
    private static Set<String> lower(Collection<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
