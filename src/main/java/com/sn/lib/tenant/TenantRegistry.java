package com.sn.lib.tenant;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Multi-tenant registry keyed by the owning plugin: the base container for every piece of
 * per-plugin state the library holds (GUIs, commands, cooldowns, listener callbacks,
 * expansions, recipes, holograms, bossbars, soft dependencies).
 *
 * <p><b>Hard rule of the shared classloader:</b> statics without an owner namespace are
 * allowed ONLY for server-wide data (SnVersion/SnCompat). Everything holding plugin,
 * player or session data goes through an instance of this class with an explicit
 * {@link Plugin} owner. Instances are static fields of library classes and live for the
 * library lifetime; never create one per context.</p>
 *
 * <p>{@link #removeOwner} removes the WHOLE key: keeping the key would keep the owning
 * plugin's classloader reachable after disable (the ManticCommand leak).</p>
 *
 * @param <T> registered value type
 */
public final class TenantRegistry<T> {

    /** Server-wide static justified: enumeration of every registry for the owner sweep. */
    private static final Set<TenantRegistry<?>> REGISTRIES = ConcurrentHashMap.newKeySet();

    private final Map<Plugin, Set<T>> byOwner = new ConcurrentHashMap<>();
    private final @Nullable Consumer<T> onSweep;

    /** Registry without a sweep callback. */
    public TenantRegistry() {
        this(null);
    }

    /**
     * Registry whose values receive {@code onSweep} when their owner key is removed via
     * {@link #removeOwner}; lets a sweep release resources (force-disable hooks, close
     * inventories) even when the owner never cleaned up.
     */
    public TenantRegistry(@Nullable Consumer<T> onSweep) {
        this.onSweep = onSweep;
        REGISTRIES.add(this);
    }

    /** Registers a value under its owner. */
    public void add(Plugin owner, T value) {
        // Mutation inside compute: atomic per key against the drop in remove(), so a
        // concurrent add never lands on a set whose key was just removed.
        byOwner.compute(owner, (key, values) -> {
            Set<T> set = values == null ? ConcurrentHashMap.<T>newKeySet() : values;
            set.add(value);
            return set;
        });
    }

    /** Unregisters a value, dropping the owner key once its set empties. */
    public void remove(Plugin owner, T value) {
        byOwner.computeIfPresent(owner, (key, values) -> {
            values.remove(value);
            return values.isEmpty() ? null : values;
        });
    }

    /** Unmodifiable view of the owner's values; empty when the owner has none. */
    public Set<T> forOwner(Plugin owner) {
        Set<T> values = byOwner.get(owner);
        return values == null ? Set.of() : Collections.unmodifiableSet(values);
    }

    /**
     * Removes the WHOLE owner key and returns the values it held, applying the sweep
     * callback (when configured) to each one. Only this owner's key is touched:
     * registrations of every other plugin stay intact (no-interference).
     */
    public Set<T> removeOwner(Plugin owner) {
        Set<T> removed = byOwner.remove(owner);
        if (removed == null) {
            return Set.of();
        }
        if (onSweep != null) {
            for (T value : removed) {
                try {
                    onSweep.accept(value);
                } catch (Throwable t) {
                    owner.getLogger().warning("Sweep of a registration failed: " + t);
                }
            }
        }
        return Collections.unmodifiableSet(removed);
    }

    /** Applies the action to every owner with an unmodifiable view of its values. */
    public void forEachOwner(BiConsumer<Plugin, Set<T>> action) {
        byOwner.forEach((owner, values) -> action.accept(owner, Collections.unmodifiableSet(values)));
    }

    /** Sweeps one owner out of EVERY registry; each registry loses only that owner's key. */
    public static void sweepOwner(Plugin owner) {
        for (TenantRegistry<?> registry : REGISTRIES) {
            registry.removeOwner(owner);
        }
    }
}
