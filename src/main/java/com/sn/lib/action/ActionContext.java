package com.sn.lib.action;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;

/**
 * Execution context of an action list run.
 *
 * @param player     player the actions run for
 * @param ctx        owning SnLib context
 * @param pageTarget pagination target (a GUI session) the page actions delegate to,
 *                   or null outside a paginated menu
 * @param clickType  click that triggered the run, or null outside a GUI click; click
 *                   guards skip their line when it is null
 * @param phs        local placeholder pairs applied to every action argument
 */
public record ActionContext(Player player, Sn ctx, @Nullable PageTarget pageTarget,
        @Nullable ClickType clickType, Ph[] phs) {

    public ActionContext {
        phs = phs == null ? new Ph[0] : phs;
    }
}
