package com.sn.lib.action;

import org.bukkit.entity.Player;

/**
 * Handler behind an action tag, built-in or registered by a consumer through
 * {@link ActionEngine#register}.
 */
@FunctionalInterface
public interface ActionHandler {

    /**
     * Runs the action for the player, always on the main thread.
     *
     * @param player  player the action runs for
     * @param arg     argument after the tag, with locals and PAPI already resolved
     * @param context execution context of the surrounding run
     */
    void run(Player player, String arg, ActionContext context);
}
