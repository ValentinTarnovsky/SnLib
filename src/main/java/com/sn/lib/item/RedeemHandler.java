package com.sn.lib.item;

import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Callback of a redeemable item registered through
 * {@link ItemRegistry#redeemable(String, RedeemSpec, RedeemHandler)}: invoked on the main
 * thread after SnLib consumed the items of one redemption, never with a zero amount.
 *
 * <p>{@code consumed} is an immutable snapshot of the removed stacks (clones sized to
 * exactly what was taken from each slot), so a handler whose items carry per-stack data
 * (a PDC value tag on a currency note, for example) can read every consumed stack and
 * aggregate; a handler that only cares about units uses {@code amount} and ignores the
 * list.</p>
 */
@FunctionalInterface
public interface RedeemHandler {

    /**
     * One completed redemption.
     *
     * @param player   the redeeming player
     * @param amount   total units consumed, always positive
     * @param consumed immutable snapshot of the consumed stacks, never empty
     */
    void redeem(Player player, int amount, List<ItemStack> consumed);
}
