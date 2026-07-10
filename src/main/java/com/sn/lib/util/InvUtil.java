package com.sn.lib.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Inventory helpers for handing items to players.
 */
public final class InvUtil {

    private InvUtil() {
    }

    /**
     * Adds the items to the player's inventory; whatever does not fit is dropped naturally
     * at the player's location so nothing is ever lost. Null and air stacks are skipped.
     */
    public static void giveItems(Player player, ItemStack... items) {
        if (player == null || items == null || items.length == 0) {
            return;
        }
        List<ItemStack> toGive = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                toGive.add(item);
            }
        }
        if (toGive.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(toGive.toArray(new ItemStack[0]));
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
