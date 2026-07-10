package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import com.sn.lib.Ph;
import com.sn.lib.item.ItemDef;
import com.sn.lib.text.SnText;

/**
 * Custom durability state of registered items, separate from vanilla damage.
 *
 * <p>The remaining durability lives in the owner-namespaced PDC key
 * {@code snlib_durability} (int), seeded at {@code custom-durability.max} when the stack
 * is created. Every damage application re-renders the definition's {@code lore-format}
 * line with {@code %durability%}/{@code %max_durability%} resolved; the lore line position
 * is remembered in a second PDC int so re-renders replace in place. Reaching 0 is reported
 * to the caller: the interact listener runs the break-actions and removes the stack from
 * the hand that used it.</p>
 */
public final class DurabilityTracker {

    /** PDC key name holding the remaining durability; namespaced per owner plugin. */
    public static final String DURABILITY_KEY = "snlib_durability";

    /** PDC key name holding the index of the rendered durability lore line. */
    private static final String LORE_LINE_KEY = "snlib_durability_line";

    private DurabilityTracker() {
    }

    /**
     * Seeds the durability tag at full max and renders the initial lore line. No-op when
     * the definition has no custom durability or the stack already carries the tag.
     */
    public static void initialize(JavaPlugin owner, ItemDef def, ItemStack stack) {
        if (def.durabilityMax() <= 0 || stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(key(owner, DURABILITY_KEY), PersistentDataType.INTEGER)) {
            return;
        }
        pdc.set(key(owner, DURABILITY_KEY), PersistentDataType.INTEGER, def.durabilityMax());
        stack.setItemMeta(meta);
        renderLore(owner, def, stack, def.durabilityMax());
    }

    /**
     * Remaining durability of the stack; an untagged stack counts as full. Returns -1
     * when the definition has no custom durability or the stack has no meta.
     */
    public static int durability(JavaPlugin owner, ItemDef def, ItemStack stack) {
        if (def.durabilityMax() <= 0 || stack == null || stack.getType().isAir()) {
            return -1;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return -1;
        }
        Integer value = meta.getPersistentDataContainer()
                .get(key(owner, DURABILITY_KEY), PersistentDataType.INTEGER);
        if (value == null) {
            return def.durabilityMax();
        }
        return Math.max(0, value);
    }

    /**
     * Subtracts {@code amount} durability (floored at 0), updates the tag and re-renders
     * the lore line.
     *
     * @return the remaining durability after the damage (0 means broken), the current
     *         value untouched when {@code amount} is not positive, or -1 when the
     *         definition has no custom durability
     */
    public static int damage(JavaPlugin owner, ItemDef def, ItemStack stack, int amount) {
        int current = durability(owner, def, stack);
        if (current < 0 || amount <= 0) {
            return current;
        }
        int remaining = Math.max(0, current - amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return current;
        }
        meta.getPersistentDataContainer()
                .set(key(owner, DURABILITY_KEY), PersistentDataType.INTEGER, remaining);
        stack.setItemMeta(meta);
        renderLore(owner, def, stack, remaining);
        return remaining;
    }

    /**
     * Renders the {@code lore-format} line with the current values, replacing the line at
     * the remembered index or appending it (and remembering where) the first time.
     */
    private static void renderLore(JavaPlugin owner, ItemDef def, ItemStack stack, int value) {
        if (def.durabilityLoreFormat().isEmpty()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        Component line = SnText.color(SnText.applyLocals(def.durabilityLoreFormat(),
                        Ph.of("durability", value), Ph.of("max_durability", def.durabilityMax())))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        List<Component> existing = meta.lore();
        List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer index = pdc.get(key(owner, LORE_LINE_KEY), PersistentDataType.INTEGER);
        if (index != null && index >= 0 && index < lore.size()) {
            lore.set(index, line);
        } else {
            lore.add(line);
            pdc.set(key(owner, LORE_LINE_KEY), PersistentDataType.INTEGER, lore.size() - 1);
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private static NamespacedKey key(JavaPlugin owner, String name) {
        return new NamespacedKey(owner, name);
    }
}
