package com.sn.lib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.tenant.internal.TenantSweeper;

/**
 * Entry point and context registry of SnLib.
 *
 * <p><b>Architectural contract (binds every module of the library):</b></p>
 * <ul>
 *   <li><b>(a) Shared classloader.</b> SnLib is a standalone plugin loaded once and shared by
 *       every consumer. Every registry holding plugin, player or session data MUST be keyed
 *       by the owning {@code Plugin}; statics without an owner namespace are allowed ONLY
 *       for server-wide data.</li>
 *   <li><b>(b) Internal packages.</b> Everything under a {@code *.internal} package is outside
 *       the semver contract and may change without notice.</li>
 *   <li><b>(c) Compatibility.</b> Runtime floor 1.20.4, target 1.21.8; unknown versions
 *       (1.22+) start with a WARN and never hard-fail. Java 21. 100% Paper/Adventure API:
 *       NMS, packets and the inventory-view class (Inventory-View) are PROHIBITED. {@code Sound},
 *       {@code Particle} and {@code ItemFlag} are treated as open sets (never
 *       switch/EnumSet over them). Lenient aliases with WARN:
 *       {@code HIDE_POTION_EFFECTS} to {@code HIDE_ADDITIONAL_TOOLTIP} and
 *       {@code REDSTONE} to {@code DUST}. Any API newer than 1.20.4 is only used behind
 *       {@code SnCompat.probe}.</li>
 *   <li><b>(d) Frozen entrypoint.</b> {@code SnPlugin} + {@code requiredApiLevel()} +
 *       {@link SnSpec} + {@link SnApi#LEVEL} never change within a major version.</li>
 * </ul>
 *
 * <p><b>Lifecycle.</b> One {@link #init} call mounts every declared module of a consumer
 * and registers its context here; {@code Sn.shutdown()} tears it down in a strict order
 * (GUIs, commands, yml flush, scheduler, locked items, player caches, database, recipes,
 * cooldowns, integrations, plugin channels, tenant registries) and detaches the key. The
 * tenant sweeper acts as a double net for owners that never shut down and cascades every
 * live context, in reverse registration order, when SnLib itself disables.</p>
 */
public final class SnLib {

    /**
     * Server-wide static justified: single context registry, keyed by owning plugin and
     * insertion-ordered so the sweeper cascade shuts down in reverse registration order.
     * The tenant sweeper removes the WHOLE key when an owner disables.
     */
    private static final Map<Plugin, Sn> CONTEXTS = new LinkedHashMap<>();

    static {
        TenantSweeper.bindContexts(new ContextAccessImpl());
    }

    private SnLib() {
    }

    /**
     * Creates and registers the context for a consumer plugin, mounting everything its
     * spec declares in one call: managed config (with the {@code update-configs} gate
     * seeded), lang, the {@code guis/} folder with its load, the items file, the
     * database module and the runtime debug command.
     *
     * <p>Package-private ON PURPOSE: the only public initialization path is extending
     * {@code com.sn.lib.SnPlugin}, which performs the API-level handshake before calling
     * this. There is no alternative public init.</p>
     *
     * <p>Idempotent per owner: a double init returns the existing live context with a
     * WARN instead of mounting a second one.</p>
     *
     * @param plugin consumer plugin that owns the new context
     * @param spec   modules the consumer declares
     * @return the registered context
     */
    static Sn init(JavaPlugin plugin, SnSpec spec) {
        synchronized (CONTEXTS) {
            Sn existing = CONTEXTS.get(plugin);
            if (existing != null) {
                plugin.getLogger().warning(
                        "SnLib.init doble: se devuelve el contexto existente sin re-montar modulos");
                return existing;
            }
        }
        Sn ctx = new Sn(plugin, spec);
        synchronized (CONTEXTS) {
            Sn raced = CONTEXTS.putIfAbsent(plugin, ctx);
            if (raced != null) {
                return raced;
            }
        }
        return ctx;
    }

    /**
     * Detaches the context key of an owner, only when it still maps to {@code ctx}; the
     * final step of {@code Sn.shutdown()}. Idempotent with the tenant sweeper's own
     * detach.
     */
    static void detach(Plugin owner, Sn ctx) {
        synchronized (CONTEXTS) {
            CONTEXTS.remove(owner, ctx);
        }
    }

    /**
     * Context of a consumer plugin, or null if that plugin never initialized against SnLib.
     */
    public static @Nullable Sn context(JavaPlugin plugin) {
        synchronized (CONTEXTS) {
            return CONTEXTS.get(plugin);
        }
    }

    /** Access the tenant sweeper uses to detach context keys without widening the API. */
    private static final class ContextAccessImpl implements TenantSweeper.ContextAccess {

        @Override
        public boolean detach(Plugin owner, Sn expected) {
            synchronized (CONTEXTS) {
                return CONTEXTS.remove(owner, expected);
            }
        }

        @Override
        public List<Sn> detachAllReversed() {
            synchronized (CONTEXTS) {
                List<Sn> all = new ArrayList<>(CONTEXTS.values());
                CONTEXTS.clear();
                Collections.reverse(all);
                return all;
            }
        }
    }
}
