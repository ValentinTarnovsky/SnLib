package com.sn.lib;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

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
 */
public final class SnLib {

    /** Server-wide static justified: single context registry, keyed by owning plugin. */
    private static final Map<JavaPlugin, Sn> CONTEXTS = new ConcurrentHashMap<>();

    private SnLib() {
    }

    /**
     * Creates and registers the context for a consumer plugin.
     *
     * <p>Package-private ON PURPOSE: the only public initialization path is extending
     * {@code com.sn.lib.SnPlugin}, which performs the API-level handshake before calling
     * this. There is no alternative public init.</p>
     *
     * @param plugin consumer plugin that owns the new context
     * @param spec   modules the consumer declares
     * @return the registered context
     */
    static Sn init(JavaPlugin plugin, SnSpec spec) {
        Sn ctx = new Sn(plugin);
        CONTEXTS.put(plugin, ctx);
        return ctx;
    }

    /**
     * Context of a consumer plugin, or null if that plugin never initialized against SnLib.
     */
    public static @Nullable Sn context(JavaPlugin plugin) {
        return CONTEXTS.get(plugin);
    }
}
