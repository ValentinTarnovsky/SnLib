package com.sn.lib.command.internal;

import org.jetbrains.annotations.Nullable;

/**
 * How {@link BukkitCommandRegistry} reports a root or alias key that another command already
 * holds, and the exact text of each report. Kept free of Bukkit types so the whole decision
 * and its wording are unit-testable without a running server.
 *
 * <p>A root built without {@code dynamic()} keeps the 1.37.0 behaviour: every collision is a
 * WARN, on every register pass. A {@code dynamic()} root is registered at runtime on purpose,
 * so losing a bare key to another plugin is expected: while its namespaced
 * {@code <plugin>:<key>} form is still its own the command stays reachable, and the collision
 * is noted ONCE per owner and key at INFO. When the namespaced key is taken too, the command
 * is unreachable under that key and the WARN stays.</p>
 */
enum CollisionNotice {

    /** Nothing to log: the collision was already noted during this enable. */
    NONE,
    /** One INFO line naming the namespaced form the command still answers on. */
    INFO,
    /** The pre-1.38.0 WARN. */
    WARN;

    /** Occupant name used when the command holding the key names no plugin. */
    static final String UNKNOWN_OCCUPANT = "another command";

    /**
     * Decides the report of one collided key.
     *
     * @param dynamic       whether the root was built with {@code dynamic()} and is not
     *                      declared in the plugin.yml
     * @param ownsNamespace whether the {@code <plugin>:<key>} form belongs to the root
     * @param alreadyNoted  whether this owner already noted this key during this enable
     */
    static CollisionNotice of(boolean dynamic, boolean ownsNamespace, boolean alreadyNoted) {
        if (!dynamic || !ownsNamespace) {
            return WARN;
        }
        return alreadyNoted ? NONE : INFO;
    }

    /** The occupant as the notices name it: its plugin, or {@link #UNKNOWN_OCCUPANT}. */
    static String occupant(@Nullable String pluginName) {
        return pluginName == null || pluginName.isBlank() ? UNKNOWN_OCCUPANT : pluginName;
    }

    /**
     * INFO of a collided root, e.g. {@code Command '/money' of SnDungeons is taken by
     * Essentials; kept it. This command answers as /sndungeons:money}.
     */
    static String rootInfo(String name, String ownerName, String occupant, String prefix) {
        return "Command '/" + name + "' of " + ownerName + " is taken by " + occupant
                + "; kept it. This command answers as /" + prefix + ":" + name;
    }

    /** The pre-1.38.0 WARN of a collided root, unchanged. */
    static String rootWarn(String name, String ownerName) {
        return "Command '/" + name + "' of " + ownerName
                + " collides with an existing command; kept the existing one";
    }

    /**
     * INFO of a collided alias, e.g. {@code Alias '/bal' of '/money' in SnDungeons is taken
     * by Essentials; kept it. This alias answers as /sndungeons:bal}.
     */
    static String aliasInfo(String alias, String rootName, String ownerName, String occupant,
            String prefix) {
        return "Alias '/" + alias + "' of '/" + rootName + "' in " + ownerName
                + " is taken by " + occupant + "; kept it. This alias answers as /" + prefix
                + ":" + alias;
    }
}
