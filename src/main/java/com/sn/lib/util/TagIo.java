package com.sn.lib.util;

import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * String tags on items via the PersistentDataContainer.
 *
 * <p>Every key is a {@code NamespacedKey(owner, key)}, so two consumers using the same key
 * name never collide: tag data is always owned by the plugin that wrote it. Values are
 * stored as {@link PersistentDataType#STRING}. Null items, air and items without meta are
 * guarded no-ops.</p>
 */
public final class TagIo {

    private TagIo() {
    }

    /**
     * Writes {@code value} under the owner's namespaced key; a null value removes the tag.
     *
     * @return the same item instance, for chaining
     */
    public static ItemStack set(ItemStack item, JavaPlugin owner, String key, String value) {
        if (value == null) {
            return remove(item, owner, key);
        }
        ItemMeta meta = metaOf(item);
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(keyOf(owner, key), PersistentDataType.STRING, value);
        item.setItemMeta(meta);
        return item;
    }

    /** Reads the tag value, or null when absent or the item is null/air/meta-less. */
    public static @Nullable String get(ItemStack item, JavaPlugin owner, String key) {
        ItemMeta meta = metaOf(item);
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(keyOf(owner, key), PersistentDataType.STRING);
    }

    /** Whether the tag is present on the item. */
    public static boolean has(ItemStack item, JavaPlugin owner, String key) {
        ItemMeta meta = metaOf(item);
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(keyOf(owner, key), PersistentDataType.STRING);
    }

    /**
     * Removes the tag if present.
     *
     * @return the same item instance, for chaining
     */
    public static ItemStack remove(ItemStack item, JavaPlugin owner, String key) {
        ItemMeta meta = metaOf(item);
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().remove(keyOf(owner, key));
        item.setItemMeta(meta);
        return item;
    }

    private static @Nullable ItemMeta metaOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item.getItemMeta();
    }

    private static NamespacedKey keyOf(JavaPlugin owner, String key) {
        return new NamespacedKey(owner, key.toLowerCase(Locale.ROOT));
    }
}
