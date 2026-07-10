package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.yml.SnYml;

/**
 * Immutable definition of one GUI, parsed from a file under {@code guis/} following the
 * golden spec ({@code docs/menu-example.yml}): title, rows, lenient inventory type, open
 * sound, menu update interval, the opt-in {@code pagination} flag, the {@code items:}
 * section and the {@code templates:} section.
 *
 * <p>{@code pagination} is resolved ONCE at load and defaults to false; page actions and
 * paged binds on sessions of a non-paginated GUI are no-ops. The definition and its
 * templates are immutable and shared by every per-viewer {@link GuiSession}.</p>
 */
public final class GuiDef {

    private final String id;
    private final String title;
    private final int rows;
    private final @Nullable InventoryType inventoryType;
    private final String openSound;
    private final int updateInterval;
    private final boolean pagination;
    private final List<GuiItemDef> items;
    private final Map<String, GuiTemplate> templates;

    private GuiDef(String id, String title, int rows, @Nullable InventoryType inventoryType,
                   String openSound, int updateInterval, boolean pagination,
                   List<GuiItemDef> items, Map<String, GuiTemplate> templates) {
        this.id = id;
        this.title = title;
        this.rows = rows;
        this.inventoryType = inventoryType;
        this.openSound = openSound;
        this.updateInterval = updateInterval;
        this.pagination = pagination;
        this.items = items;
        this.templates = templates;
    }

    /** Parses the whole GUI file; every malformed field WARNs and falls back, never throws. */
    static GuiDef parse(Sn ctx, String id, SnYml yml) {
        Consumer<String> warn = message -> ctx.plugin().getLogger()
                .warning("[gui " + id + "] " + message);
        ConfigurationSection root = yml.getSection("");
        if (root == null) {
            warn.accept("Archivo vacio o ilegible; se usa un gui por defecto sin items");
            return new GuiDef(id, "Menu", 3, null, "", 0, false, List.of(), Map.of());
        }
        String title = root.getString("title", "Menu");
        int rows = root.getInt("rows", 3);
        if (rows < 1 || rows > 6) {
            warn.accept("rows " + rows + " fuera de rango 1-6; usando 3");
            rows = 3;
        }
        InventoryType type = parseInventoryType(root.getString("inventory-type", ""), warn);
        String openSound = root.getString("open-sound", "");
        int updateInterval = Math.max(0, root.getInt("update-interval", 0));
        boolean pagination = root.getBoolean("pagination", false);
        List<GuiItemDef> items = new ArrayList<>();
        ConfigurationSection itemsSection = root.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                GuiItemDef item = GuiItemDef.parse(yml, "items." + key, key, warn);
                if (item == null) {
                    continue;
                }
                if (!item.hasSlots()) {
                    warn.accept("Item '" + key + "' sin slots validos; no se renderiza");
                    continue;
                }
                items.add(item);
            }
        }
        Map<String, GuiTemplate> templates = new LinkedHashMap<>();
        ConfigurationSection templatesSection = root.getConfigurationSection("templates");
        if (templatesSection != null) {
            for (String key : templatesSection.getKeys(false)) {
                GuiItemDef item = GuiItemDef.parse(yml, "templates." + key, key, warn);
                if (item != null) {
                    templates.put(key, new GuiTemplate(item));
                }
            }
        }
        return new GuiDef(id, title, rows, type, openSound, updateInterval, pagination,
                List.copyOf(items), Map.copyOf(templates));
    }

    /**
     * Lenient inventory type resolution: empty or CHEST means a chest sized by rows;
     * unknown names WARN and fall back to chest. Resolved by individual valueOf in
     * try/catch, never switch, so the enum stays open across versions.
     */
    private static @Nullable InventoryType parseInventoryType(String raw, Consumer<String> warn) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("CHEST")) {
            return null;
        }
        try {
            return InventoryType.valueOf(name);
        } catch (IllegalArgumentException e) {
            warn.accept("inventory-type invalido '" + raw + "'; usando CHEST");
            return null;
        }
    }

    /** GUI id: its file name without the {@code .yml} extension. */
    public String id() {
        return id;
    }

    /** Raw title; sessions resolve its placeholders per viewer at render time. */
    public String title() {
        return title;
    }

    /** Chest rows (1-6); only used when {@link #inventoryType()} is null. */
    public int rows() {
        return rows;
    }

    /** Non-chest inventory type, or null for a chest sized by {@link #rows()}. */
    public @Nullable InventoryType inventoryType() {
        return inventoryType;
    }

    /** Open sound spec ({@code "SOUND_ID [vol] [pitch]"}); empty plays nothing. */
    public String openSound() {
        return openSound;
    }

    /** Menu re-render interval in ticks; 0 disables the menu timer. */
    public int updateInterval() {
        return updateInterval;
    }

    /** Whether this menu opted in to pagination; default false. */
    public boolean pagination() {
        return pagination;
    }

    /** Items of the {@code items:} section, in declaration order. */
    public List<GuiItemDef> items() {
        return items;
    }

    /** Template declared under {@code templates:} with the given id, or null. */
    public @Nullable GuiTemplate template(String templateId) {
        return templateId == null ? null : templates.get(templateId);
    }

    /** Every template of the {@code templates:} section, keyed by id. */
    public Map<String, GuiTemplate> templates() {
        return templates;
    }
}
