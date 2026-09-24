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
 *
 * <p>A root of a plugin that declared {@code commandPriority()} does not collide with the
 * command of another plugin: it takes the key ({@link #takesOver(boolean, Occupant)}) and
 * notes that once per owner and key at INFO ({@link #rootTakeover}, {@link #aliasTakeover}).
 * Every other occupant keeps the key and gets the report above.</p>
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
     * Who holds a key a root wants, as the command priority decision sees it.
     */
    enum Occupant {
        /** The root itself, another root of the same plugin, or a command of that plugin. */
        OWN,
        /**
         * A class of the server itself: a vanilla, Bukkit or Paper command, the wrapper of a
         * command registered through Paper's Brigadier API, a {@code commands.yml} alias; or a
         * command that names no plugin.
         */
        SERVER,
        /**
         * A command another plugin provides: its plugin.yml command (Essentials' {@code /money})
         * or any {@code Command} or {@code BukkitCommand} class that plugin registered.
         */
        PLUGIN,
        /** A root of another SnLib plugin without command priority. */
        SNLIB_ROOT,
        /** A root of another SnLib plugin that declared command priority too. */
        SNLIB_PRIORITY_ROOT
    }

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

    /**
     * Whether a root takes a key from its occupant: only under command priority, and only
     * from another plugin's command or from a root of another SnLib plugin without priority.
     * A plugin with priority keeps the key it holds, so two of them never trade it back and
     * forth. A command of a disabled plugin is taken too: it can no longer run.
     */
    static boolean takesOver(boolean priority, Occupant occupant) {
        return priority && (occupant == Occupant.PLUGIN || occupant == Occupant.SNLIB_ROOT);
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

    /**
     * INFO of a root that took its name, e.g. {@code Command '/money' of SnDungeons took the
     * name from Essentials (command priority); Essentials still answers as /essentials:money}.
     *
     * @param occupantPrefix namespace the displaced command still answers under, or null when
     *                       its {@code <prefix>:<name>} key is not that command (the line then
     *                       ends at {@code (command priority)})
     */
    static String rootTakeover(String name, String ownerName, String occupant,
            @Nullable String occupantPrefix) {
        return "Command '/" + name + "' of " + ownerName + " took the name from " + occupant
                + " (command priority)" + stillAnswers(occupant, occupantPrefix, name);
    }

    /**
     * INFO of an alias that took its key, e.g. {@code Alias '/bal' of '/money' in SnDungeons
     * took the name from Essentials (command priority); Essentials still answers as
     * /essentials:bal}.
     *
     * @param occupantPrefix see {@link #rootTakeover}
     */
    static String aliasTakeover(String alias, String rootName, String ownerName,
            String occupant, @Nullable String occupantPrefix) {
        return "Alias '/" + alias + "' of '/" + rootName + "' in " + ownerName
                + " took the name from " + occupant + " (command priority)"
                + stillAnswers(occupant, occupantPrefix, alias);
    }

    private static String stillAnswers(String occupant, @Nullable String occupantPrefix,
            String key) {
        return occupantPrefix == null ? ""
                : "; " + occupant + " still answers as /" + occupantPrefix + ":" + key;
    }
}
