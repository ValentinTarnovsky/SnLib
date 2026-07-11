package com.sn.lib.util;

import java.util.Locale;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/**
 * Armour helpers over stacks and the player's worn set.
 *
 * <p>Slot resolution matches by Material name suffix ({@code _HELMET}/{@code _HEAD}/
 * {@code _SKULL}/{@code CARVED_PUMPKIN} to HEAD, {@code _CHESTPLATE}/{@code ELYTRA} to
 * CHEST, {@code _LEGGINGS} to LEGS, {@code _BOOTS} to FEET). Material is treated as an
 * open enum (name checks, never switch/EnumSet over its constants, no
 * {@code Material.values()} scan at class-load), so constants added by newer servers
 * never break the mapping. Typical use: full-set checks inside a
 * {@code SnArmourEquipEvent} handler.</p>
 */
public final class ArmourUtil {

    private ArmourUtil() {
    }

    /**
     * Armour slot the item auto-equips to, matched by Material name suffix, or null
     * when the item is null, air or not an armour piece.
     */
    public static @Nullable EquipmentSlot slotOf(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String name = item.getType().name();
        if (name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                || name.equals("CARVED_PUMPKIN")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /** Whether the item maps to an armour slot ({@link #slotOf} is non-null). */
    public static boolean isArmour(@Nullable ItemStack item) {
        return slotOf(item) != null;
    }

    /**
     * Whether the player's four armour slots (helmet, chestplate, leggings, boots) are
     * all non-empty (null and air count as empty). A null player returns false.
     */
    public static boolean isWearingFullSet(Player player) {
        return isWearingFullSet(player, null);
    }

    /**
     * Like {@link #isWearingFullSet(Player)} and additionally every worn piece's
     * Material name starts with {@code materialPrefix} (normalized to uppercase, e.g.
     * {@code "DIAMOND_"}). A null or blank prefix delegates to the prefix-less check.
     */
    public static boolean isWearingFullSet(Player player, @Nullable String materialPrefix) {
        if (player == null) {
            return false;
        }
        String prefix = materialPrefix == null || materialPrefix.isBlank()
                ? null : materialPrefix.trim().toUpperCase(Locale.ROOT);
        PlayerInventory inventory = player.getInventory();
        return matches(inventory.getHelmet(), prefix)
                && matches(inventory.getChestplate(), prefix)
                && matches(inventory.getLeggings(), prefix)
                && matches(inventory.getBoots(), prefix);
    }

    private static boolean matches(@Nullable ItemStack piece, @Nullable String prefix) {
        if (piece == null || piece.getType().isAir()) {
            return false;
        }
        return prefix == null || piece.getType().name().startsWith(prefix);
    }
}
