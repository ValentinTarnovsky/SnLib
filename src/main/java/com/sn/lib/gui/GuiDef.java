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
 * sound, menu update interval, the opt-in {@code pagination} and {@code strict-clicks}
 * flags, the {@code items:} section and the {@code templates:} section.
 *
 * <p>{@code pagination} is resolved ONCE at load and defaults to false; page actions and
 * paged binds on sessions of a non-paginated GUI are no-ops. The definition and its
 * templates are immutable and shared by every per-viewer {@link GuiSession}.</p>
 *
 * <pre>
 * Golden spec checklist (docs/menu-example.yml) - field by field, where it parses:
 *   title, rows (1-6), open-sound, update-interval,     -> GuiDef.parse
 *     inventory-type (lenient valueOf), pagination,
 *     strict-clicks (opt-in per menu, default false)
 *   layout (1-6 strings of up to 9 chars each; ' ' is   -> GuiDef.parse (ASCII mask; rows
 *     an empty cell; every key char maps to its cells)     derives from the row count)
 *   paged-key (one layout char at menu level: its       -> GuiDef.parse into pagedSlots(),
 *     cells are the target of the no-slots bindPaged)      consumed by GuiSession.bindPaged
 *   items.&lt;id&gt;:
 *     display-name, material (basehead/base64), lore,   -> SnItem.parse via GuiItemDef.render
 *       custom-model-data, amount, glow, enchantments,     (re-read per viewer: locals,
 *       flags (HIDE_ALL, HIDE_POTION_EFFECTS alias),        PAPI, [rgb], [center],
 *       color RGB/HEX, trim-pattern, trim-material,         MiniMessage + legacy)
 *       potion-effects
 *     slots (int, list, ranges "0-8", mixed) or key     -> GuiItemDef.parse via SlotParser
 *       (one layout char; declared slots win over key)     or the menu layout map
 *     update-interval (per item)                        -> GuiItemDef.parse
 *     view-requirements, click-requirements             -> GuiItemDef.parse via RequirementEngine
 *     click-actions, deny-actions                       -> GuiItemDef.parse; run by ActionEngine
 *       ([player], [player-as-op], [console], [message], [sound], [close], [open],
 *        [connect], [broadcastmessage], [actionbar], [title], [right-click]/[left-click]/
 *        [shift-*-click] filters, [next-page], [previous-page], [set-page], [refresh-page],
 *        [refresh-menu], custom tags via GuiManager.registerAction)
 *     per-click matrix (specific-over-generic,          -> GuiItemDef.parse; resolved per
 *       field by field): right-click-actions,              ClickType by clickActionsFor /
 *       right-click-requirements,                          clickRequirementFor /
 *       right-click-deny-actions and the same three        denyActionsFor at click time
 *       lists for left-click, shift-right-click,
 *       shift-left-click and middle-click
 *     previous-page / next-page navigation items        -> GuiItemDef.parse (NavKind detected
 *       with nav-disabled override (same appearance        from the pagination actions of
 *       fields, no slots, no actions)                       every list; nav-disabled recursive)
 *   templates.&lt;id&gt; (same fields, no slots)              -> GuiDef.parse into GuiTemplate;
 *                                                           slots assigned via session binds
 *   [rgb], [center][rgb] composable in any order and    -> SnText pipeline used by every
 *     MiniMessage mixed with legacy codes                   string of the item render
 * </pre>
 */
public final class GuiDef {

    private final String id;
    private final String title;
    private final int rows;
    private final @Nullable InventoryType inventoryType;
    private final String openSound;
    private final int updateInterval;
    private final boolean pagination;
    private final boolean strictClicks;
    private final int[] pagedSlots;
    private final List<GuiItemDef> items;
    private final Map<String, GuiTemplate> templates;

    private GuiDef(String id, String title, int rows, @Nullable InventoryType inventoryType,
                   String openSound, int updateInterval, boolean pagination,
                   boolean strictClicks, int[] pagedSlots, List<GuiItemDef> items,
                   Map<String, GuiTemplate> templates) {
        this.id = id;
        this.title = title;
        this.rows = rows;
        this.inventoryType = inventoryType;
        this.openSound = openSound;
        this.updateInterval = updateInterval;
        this.pagination = pagination;
        this.strictClicks = strictClicks;
        this.pagedSlots = pagedSlots;
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
            return new GuiDef(id, "Menu", 3, null, "", 0, false, false, new int[0],
                    List.of(), Map.of());
        }
        String title = root.getString("title", "Menu");
        List<String> layoutRows = truncateLayout(root.getStringList("layout"), warn);
        Map<Character, int[]> keySlots = layoutKeys(layoutRows);
        int rows = root.getInt("rows", 3);
        if (!layoutRows.isEmpty()) {
            if (root.isSet("rows") && rows != layoutRows.size()) {
                warn.accept("rows " + rows + " contradice layout de " + layoutRows.size()
                        + " filas; usando " + layoutRows.size());
            }
            rows = layoutRows.size();
        } else if (rows < 1 || rows > 6) {
            warn.accept("rows " + rows + " fuera de rango 1-6; usando 3");
            rows = 3;
        }
        InventoryType type = parseInventoryType(root.getString("inventory-type", ""), warn);
        if (!layoutRows.isEmpty() && type != null) {
            warn.accept("layout asume grilla de cofre de 9 columnas; con inventory-type "
                    + type + " los slots fuera de rango no se renderizan");
        }
        String openSound = root.getString("open-sound", "");
        int updateInterval = Math.max(0, root.getInt("update-interval", 0));
        boolean pagination = root.getBoolean("pagination", false);
        boolean strictClicks = root.getBoolean("strict-clicks", false);
        int[] pagedSlots = parsePagedKey(root, keySlots, !layoutRows.isEmpty(), pagination, warn);
        List<GuiItemDef> items = new ArrayList<>();
        ConfigurationSection itemsSection = root.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                GuiItemDef item = GuiItemDef.parse(yml, "items." + key, key, keySlots, warn);
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
                GuiItemDef item = GuiItemDef.parse(yml, "templates." + key, key, null, warn);
                if (item != null) {
                    templates.put(key, new GuiTemplate(item));
                }
            }
        }
        return new GuiDef(id, title, rows, type, openSound, updateInterval, pagination,
                strictClicks, pagedSlots, List.copyOf(items), Map.copyOf(templates));
    }

    /**
     * Truncates the raw {@code layout:} list to the 6x9 chest grid with a WARN per
     * overflow: at most 6 rows and at most 9 characters per row. An empty list means
     * the menu declared no layout.
     */
    private static List<String> truncateLayout(List<String> raw, Consumer<String> warn) {
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> rows = raw;
        if (rows.size() > 6) {
            warn.accept("layout tiene " + rows.size() + " filas; se truncan a 6");
            rows = rows.subList(0, 6);
        }
        List<String> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i) == null ? "" : rows.get(i);
            if (row.length() > 9) {
                warn.accept("fila " + (i + 1) + " del layout tiene " + row.length()
                        + " caracteres; se trunca a 9");
                row = row.substring(0, 9);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * Maps every non-space layout character to the slots of its cells, in ascending
     * row-major order (same geometry as {@link GuiMask}: slot = row * 9 + column). A key
     * appearing in N cells accumulates the N slots.
     */
    private static Map<Character, int[]> layoutKeys(List<String> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Character, List<Integer>> collected = new LinkedHashMap<>();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            for (int column = 0; column < line.length(); column++) {
                char key = line.charAt(column);
                if (key == ' ') {
                    continue;
                }
                collected.computeIfAbsent(key, unused -> new ArrayList<>())
                        .add(row * 9 + column);
            }
        }
        Map<Character, int[]> keySlots = new LinkedHashMap<>();
        for (Map.Entry<Character, List<Integer>> entry : collected.entrySet()) {
            List<Integer> cells = entry.getValue();
            int[] slots = new int[cells.size()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = cells.get(i);
            }
            keySlots.put(entry.getKey(), slots);
        }
        return keySlots;
    }

    /**
     * Resolves the menu-level {@code paged-key:} against the layout map: exactly one
     * character that appears in the layout. Every invalid combination WARNs and yields
     * an empty array; a valid key with {@code pagination: false} WARNs but the value is
     * kept (the real gate is the existing one in {@code GuiSession.bindPaged}).
     */
    private static int[] parsePagedKey(ConfigurationSection root, Map<Character, int[]> keySlots,
                                       boolean hasLayout, boolean pagination,
                                       Consumer<String> warn) {
        String raw = root.getString("paged-key", "");
        if (raw.isEmpty()) {
            return new int[0];
        }
        if (!hasLayout) {
            warn.accept("paged-key declarado sin layout; ignorado");
            return new int[0];
        }
        String trimmed = raw.trim();
        if (trimmed.length() != 1) {
            warn.accept("paged-key '" + raw + "' invalido (debe ser 1 caracter); ignorado");
            return new int[0];
        }
        char key = trimmed.charAt(0);
        int[] slots = keySlots.get(key);
        if (slots == null) {
            warn.accept("paged-key '" + key + "' no aparece en layout; ignorado");
            return new int[0];
        }
        if (!pagination) {
            warn.accept("paged-key declarado con pagination false; bindPaged quedara"
                    + " ignorado hasta activar pagination");
        }
        return slots.clone();
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

    /**
     * Whether this menu opted in to strict clicks; default false. With true, a click
     * outside the four basic mouse clicks (LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT) is
     * discarded unless a declared specific actions list covers it.
     */
    public boolean strictClicks() {
        return strictClicks;
    }

    /**
     * Target slots of the no-slots {@code bindPaged}, resolved from the layout's
     * {@code paged-key}; empty when the menu declares no paged-key.
     */
    public int[] pagedSlots() {
        return pagedSlots.clone();
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
