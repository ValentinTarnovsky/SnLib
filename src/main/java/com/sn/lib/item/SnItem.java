package com.sn.lib.item;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import com.sn.lib.Ph;
import com.sn.lib.compat.SnCompat;
import com.sn.lib.compat.SnVersion;
import com.sn.lib.text.SnText;
import com.sn.lib.util.HeadUtil;
import com.sn.lib.yml.SnYml;

/**
 * Fluent builder for physical item stacks covering the full appearance section of the
 * golden spec ({@code docs/item-example.yml}).
 *
 * <p>Strings (name, lore) go through the SnLib text pipeline ({@code [rgb]},
 * {@code [center]}, legacy codes, MiniMessage) and render non-italic unless the input
 * asks for italics. Materials, enchantments, potion effects and trims resolve leniently
 * via Registry/NamespacedKey with legacy-name fallbacks; an unresolvable id logs one WARN
 * and is skipped, never thrown.</p>
 *
 * <p>Compat: {@code setEnchantmentGlintOverride} and {@code setMaxStackSize} (1.20.5+) go
 * through {@link SnCompat#probe}; on 1.20.4 glow degrades to a real vanilla enchant plus
 * {@code HIDE_ENCHANTS} and max-stack-size is skipped, each with one WARN. Trim lookup
 * prefers {@link RegistryAccess} ({@code Registry.TRIM_*} is deprecated since 1.20.6) and
 * falls back to the legacy fields on older servers. {@link ItemFlag} is treated as an open
 * enum: individual {@code valueOf} in try/catch with the lenient
 * {@code HIDE_POTION_EFFECTS}/{@code HIDE_ADDITIONAL_TOOLTIP} alias, never switch/EnumSet.</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: the WARN dedup set records facts
 * about this server's registries, not about a consumer.</p>
 */
public final class SnItem {

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Tooltip-hiding flag names across the supported range; resolved one by one with
     * {@code valueOf} in try/catch so names absent on this server are skipped silently.
     */
    private static final String[] TOOLTIP_FLAGS = {
            "HIDE_ENCHANTS", "HIDE_ATTRIBUTES", "HIDE_UNBREAKABLE", "HIDE_DESTROYS",
            "HIDE_PLACED_ON", "HIDE_POTION_EFFECTS", "HIDE_ADDITIONAL_TOOLTIP",
            "HIDE_DYE", "HIDE_ARMOR_TRIM", "HIDE_STORED_ENCHANTS"
    };

    private final Material material;
    private String name;
    private final List<String> lore = new ArrayList<>();
    private int amount = 1;
    private boolean glow;
    private final Map<String, Integer> enchants = new LinkedHashMap<>();
    private final List<String> flags = new ArrayList<>();
    private boolean hideTooltipFlags;
    private String color;
    private String trimPattern;
    private String trimMaterial;
    private final List<String> potionEffects = new ArrayList<>();
    private Integer modelData;
    private String headBase64;
    private Boolean unbreakable;
    private Integer maxStackSize;
    private String equipmentSlot;

    private SnItem(Material material) {
        this.material = material;
    }

    /** Starts a builder; a null material falls back to {@code STONE}. */
    public static SnItem builder(Material material) {
        return new SnItem(material == null ? Material.STONE : material);
    }

    /** Reads every appearance field from the root of {@code yml}; see the path overload. */
    public static SnItem fromConfig(SnYml yml, @Nullable Player viewer, Ph... phs) {
        return fromConfig(yml, null, viewer, phs);
    }

    /**
     * Maps every appearance field of the golden spec found under {@code path}: display-name,
     * material (with the {@code texture-}/{@code basehead-}/{@code base64-} head convention),
     * custom-model-data (only when set), amount, glow, lore, enchantments, flags, color,
     * trim-pattern/trim-material, potion-effects, unbreakable, max-stack-size and
     * equipment-slot. Strings resolve through the yml pipeline with {@code viewer} plus the
     * extra local placeholders {@code phs}.
     *
     * @param path section path inside the file; null or empty reads from the root
     */
    public static SnItem fromConfig(SnYml yml, @Nullable String path, @Nullable Player viewer,
                                    Ph... phs) {
        String p = path == null || path.isEmpty() ? "" : path + ".";
        String rawMaterial = SnText.applyLocals(yml.getString(p + "material", "STONE", viewer), phs);
        SnItem item;
        if (HeadUtil.extractTextureValue(rawMaterial) != null) {
            item = builder(Material.PLAYER_HEAD).headBase64(rawMaterial);
        } else {
            item = builder(resolveMaterial(rawMaterial));
        }
        String displayName = SnText.applyLocals(yml.getString(p + "display-name", "", viewer), phs);
        if (!displayName.isEmpty()) {
            item.name(displayName);
        }
        List<String> loreLines = yml.getStringList(p + "lore", List.of(), viewer);
        for (String line : loreLines) {
            item.lore(SnText.applyLocals(line, phs));
        }
        item.amount(yml.getInt(p + "amount", 1));
        if (yml.isSet(p + "custom-model-data")) {
            item.modelData(yml.getInt(p + "custom-model-data", 0));
        }
        if (yml.getBoolean(p + "glow", false)) {
            item.glow();
        }
        readEnchantments(item, yml.getStringList(p + "enchantments", List.of()));
        item.flags(yml.getStringList(p + "flags", List.of()));
        String rawColor = yml.getString(p + "color", "");
        if (!rawColor.isEmpty()) {
            item.color(rawColor);
        }
        item.trim(yml.getString(p + "trim-pattern", ""), yml.getString(p + "trim-material", ""));
        item.potionEffects(yml.getStringList(p + "potion-effects", List.of()));
        if (yml.isSet(p + "unbreakable")) {
            item.unbreakable(yml.getBoolean(p + "unbreakable", false));
        }
        if (yml.isSet(p + "max-stack-size")) {
            item.maxStackSize(yml.getInt(p + "max-stack-size", 0));
        }
        String slot = yml.getString(p + "equipment-slot", "");
        if (!slot.isEmpty()) {
            item.equipmentSlot(slot);
        }
        return item;
    }

    /** Display name; rendered through the text pipeline, non-italic unless asked. */
    public SnItem name(String name) {
        this.name = name;
        return this;
    }

    /** Appends lore lines; each renders through the text pipeline. */
    public SnItem lore(List<String> lines) {
        if (lines != null) {
            for (String line : lines) {
                this.lore.add(line == null ? "" : line);
            }
        }
        return this;
    }

    /** Varargs convenience for {@link #lore(List)}. */
    public SnItem lore(String... lines) {
        return lines == null ? this : lore(List.of(lines));
    }

    /** Stack amount, floored at 1. */
    public SnItem amount(int amount) {
        this.amount = amount;
        return this;
    }

    /**
     * Enchantment glint. Uses {@code setEnchantmentGlintOverride} when present (1.20.5+);
     * on 1.20.4 degrades to a real vanilla enchant plus {@code HIDE_ENCHANTS} with one WARN.
     */
    public SnItem glow() {
        this.glow = true;
        return this;
    }

    /** Adds an enchantment by lenient id (Registry key or legacy Bukkit name). */
    public SnItem enchant(String id, int level) {
        if (id != null && !id.isBlank()) {
            enchants.put(id.trim(), level);
        }
        return this;
    }

    /**
     * Adds item flags by name. {@code HIDE_ALL} expands to every {@link ItemFlag#values()}
     * of this server; unknown names try the {@code HIDE_POTION_EFFECTS}/
     * {@code HIDE_ADDITIONAL_TOOLTIP} alias before one WARN.
     */
    public SnItem flags(List<String> names) {
        if (names != null) {
            for (String flag : names) {
                if (flag != null && !flag.isBlank()) {
                    flags.add(flag.trim());
                }
            }
        }
        return this;
    }

    /** Adds every tooltip-hiding flag known to this server; see {@link #TOOLTIP_FLAGS}. */
    public SnItem hideAllTooltipFlags() {
        this.hideTooltipFlags = true;
        return this;
    }

    /**
     * Tint for color-capable metas (leather armor, potions). Accepts {@code "R, G, B"} and
     * hex {@code "RRGGBB"}/{@code "#RRGGBB"}.
     */
    public SnItem color(String color) {
        this.color = color;
        return this;
    }

    /**
     * Armor trim; both values must be given together, {@code NONE} or empty disables.
     * Lookup prefers {@link RegistryAccess} with the legacy {@code Registry.TRIM_*} fields
     * as lenient fallback.
     */
    public SnItem trim(String pattern, String material) {
        this.trimPattern = pattern;
        this.trimMaterial = material;
        return this;
    }

    /**
     * Custom potion effects for {@link PotionMeta} items. Entries follow the golden spec
     * flat shape {@code [effect-id, level, duration]}; level defaults to 1 (amplifier is
     * {@code level - 1}) and duration to 200 ticks.
     */
    public SnItem potionEffects(List<String> effects) {
        if (effects != null) {
            for (String effect : effects) {
                if (effect != null && !effect.isBlank()) {
                    potionEffects.add(effect.trim());
                }
            }
        }
        return this;
    }

    /** Custom model data; only stamps the meta when explicitly set. */
    public SnItem modelData(int modelData) {
        this.modelData = modelData;
        return this;
    }

    /** Head texture accepted by {@link HeadUtil#extractTextureValue}; requires PLAYER_HEAD. */
    public SnItem headBase64(String value) {
        this.headBase64 = value;
        return this;
    }

    /** Vanilla unbreakable flag. */
    public SnItem unbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
        return this;
    }

    /** Max stack size via probe; on 1.20.4 the value is skipped with one WARN. */
    public SnItem maxStackSize(int maxStackSize) {
        this.maxStackSize = maxStackSize;
        return this;
    }

    /**
     * Declared equipment slot of the golden spec (MAINHAND, OFFHAND, HEAD, CHEST, LEGS,
     * FEET). Validated leniently at build with one WARN on typos; the stack itself is not
     * altered, enforcement belongs to the item definition layer.
     */
    public SnItem equipmentSlot(String slot) {
        this.equipmentSlot = slot;
        return this;
    }

    /** Builds the stack, applying every configured field with lenient degradation. */
    public ItemStack build() {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.displayName(render(name));
        }
        if (!lore.isEmpty()) {
            List<Component> rendered = new ArrayList<>(lore.size());
            for (String line : lore) {
                rendered.add(render(line));
            }
            meta.lore(rendered);
        }
        applyEnchants(meta);
        if (glow) {
            applyGlow(meta);
        }
        applyFlags(meta);
        if (color != null && !color.isBlank()) {
            applyColor(meta);
        }
        applyTrim(meta);
        applyPotionEffects(meta);
        if (modelData != null) {
            meta.setCustomModelData(modelData);
        }
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (maxStackSize != null) {
            applyMaxStackSize(meta);
        }
        if (headBase64 != null) {
            applyHead(meta);
        }
        validateEquipmentSlot();
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Lenient spec-name to {@link EquipmentSlot}: {@code MAINHAND} maps to {@code HAND} and
     * {@code OFFHAND} to {@code OFF_HAND}; unknown names yield null.
     */
    public static @Nullable EquipmentSlot parseEquipmentSlot(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String slotName = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (slotName.equals("MAINHAND")) {
            slotName = "HAND";
        } else if (slotName.equals("OFFHAND")) {
            slotName = "OFF_HAND";
        }
        try {
            return EquipmentSlot.valueOf(slotName);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Component render(String line) {
        return SnText.color(line)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Walks the flat {@code [id, level, id, level]} shape of the golden spec. */
    private static void readEnchantments(SnItem item, List<String> raw) {
        List<String> tokens = tokenize(raw);
        int i = 0;
        while (i < tokens.size()) {
            String id = tokens.get(i++);
            if (isInt(id)) {
                warnOnce("ench-shape:" + id, "Nivel de encantamiento '" + id
                        + "' sin id previo; se ignora (formato esperado: [id, nivel, ...])");
                continue;
            }
            int level = 1;
            if (i < tokens.size() && isInt(tokens.get(i))) {
                level = Integer.parseInt(tokens.get(i).trim());
                i++;
            }
            item.enchant(id, level);
        }
    }

    private void applyEnchants(ItemMeta meta) {
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            Enchantment enchantment = resolveEnchant(entry.getKey());
            if (enchantment == null) {
                warnOnce("ench:" + entry.getKey(), "Encantamiento invalido '" + entry.getKey()
                        + "': no se resolvio por Registry ni por nombre legacy; se ignora");
                continue;
            }
            meta.addEnchant(enchantment, Math.max(1, entry.getValue()), true);
        }
    }

    private void applyGlow(ItemMeta meta) {
        Method glint = SnCompat.probe(ItemMeta.class, "setEnchantmentGlintOverride", Boolean.class);
        if (glint != null) {
            try {
                glint.invoke(meta, Boolean.TRUE);
                return;
            } catch (ReflectiveOperationException e) {
                warnOnce("glint-invoke", "setEnchantmentGlintOverride fallo (" + e
                        + "); degradando a encantamiento real + HIDE_ENCHANTS");
            }
        }
        if (!meta.hasEnchants()) {
            meta.addEnchant(Enchantment.LURE, 1, true);
        }
        ItemFlag hide = resolveFlag("HIDE_ENCHANTS");
        if (hide != null) {
            meta.addItemFlags(hide);
        }
    }

    private void applyFlags(ItemMeta meta) {
        if (hideTooltipFlags) {
            applyTooltipFlags(meta);
        }
        for (String raw : flags) {
            String flagName = raw.toUpperCase(Locale.ROOT);
            if (flagName.equals("HIDE_ALL")) {
                meta.addItemFlags(ItemFlag.values());
                continue;
            }
            ItemFlag flag = resolveFlag(flagName);
            if (flag != null) {
                meta.addItemFlags(flag);
            }
        }
    }

    private static void applyTooltipFlags(ItemMeta meta) {
        for (String flagName : TOOLTIP_FLAGS) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flagName));
            } catch (IllegalArgumentException absentOnThisServer) {
                // Nombre de otra rama de versiones; se salta sin WARN.
            }
        }
    }

    private static @Nullable ItemFlag resolveFlag(String flagName) {
        try {
            return ItemFlag.valueOf(flagName);
        } catch (IllegalArgumentException unknown) {
            String alias = flagAlias(flagName);
            if (alias != null) {
                try {
                    ItemFlag aliased = ItemFlag.valueOf(alias);
                    warnOnce("flag-alias:" + flagName, "ItemFlag " + flagName
                            + " no existe en este server; aliasado a " + alias);
                    return aliased;
                } catch (IllegalArgumentException aliasAlsoUnknown) {
                    // Cae al WARN generico.
                }
            }
            warnOnce("flag:" + flagName, "ItemFlag invalido '" + flagName + "'; se ignora");
            return null;
        }
    }

    private static @Nullable String flagAlias(String flagName) {
        if (flagName.equals("HIDE_POTION_EFFECTS")) {
            return "HIDE_ADDITIONAL_TOOLTIP";
        }
        if (flagName.equals("HIDE_ADDITIONAL_TOOLTIP")) {
            return "HIDE_POTION_EFFECTS";
        }
        return null;
    }

    private void applyColor(ItemMeta meta) {
        Color parsed = parseColor(color);
        if (parsed == null) {
            warnOnce("color:" + color, "Color invalido '" + color
                    + "' (se espera 'R, G, B' o hex 'RRGGBB'); se ignora");
            return;
        }
        if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(parsed);
        } else if (meta instanceof PotionMeta potion) {
            potion.setColor(parsed);
        } else {
            warnOnce("color-meta:" + material, "color definido para " + material
                    + " que no soporta tintado; se ignora");
        }
    }

    private static @Nullable Color parseColor(String raw) {
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            if (value.indexOf(',') >= 0) {
                String[] parts = value.split(",");
                if (parts.length != 3) {
                    return null;
                }
                return Color.fromRGB(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
            }
            if (value.length() == 6) {
                return Color.fromRGB(Integer.parseInt(value, 16));
            }
        } catch (IllegalArgumentException invalid) {
            // Numero mal formado o canal fuera de 0-255; el caller avisa con UN WARN.
        }
        return null;
    }

    private void applyTrim(ItemMeta meta) {
        boolean hasPattern = isSpecified(trimPattern);
        boolean hasMaterial = isSpecified(trimMaterial);
        if (!hasPattern && !hasMaterial) {
            return;
        }
        if (!(meta instanceof ArmorMeta armor)) {
            warnOnce("trim-meta:" + material, "trim definido para " + material
                    + " que no es armadura; se ignora");
            return;
        }
        if (!hasPattern || !hasMaterial) {
            warnOnce("trim-pair:" + trimPattern + "/" + trimMaterial,
                    "trim-pattern y trim-material deben definirse juntos; se ignora");
            return;
        }
        TrimPattern pattern = resolveTrimPattern(trimPattern);
        if (pattern == null) {
            warnOnce("trim-pattern:" + trimPattern, "trim-pattern invalido '" + trimPattern
                    + "'; se ignora");
            return;
        }
        TrimMaterial trimMat = resolveTrimMaterial(trimMaterial);
        if (trimMat == null) {
            warnOnce("trim-material:" + trimMaterial, "trim-material invalido '" + trimMaterial
                    + "'; se ignora");
            return;
        }
        armor.setTrim(new ArmorTrim(trimMat, pattern));
    }

    private static boolean isSpecified(String value) {
        return value != null && !value.isBlank() && !value.trim().equalsIgnoreCase("NONE");
    }

    private static @Nullable TrimPattern resolveTrimPattern(String rawName) {
        NamespacedKey key = registryKey(rawName);
        if (key == null) {
            return null;
        }
        if (SnVersion.supports(20, 6)) {
            try {
                TrimPattern modern = RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.TRIM_PATTERN).get(key);
                if (modern != null) {
                    return modern;
                }
            } catch (Throwable registryAccessUnavailable) {
                // Server sin RegistryAccess pese a la version: cae al campo legacy.
            }
        }
        return legacyTrimPattern(key);
    }

    private static @Nullable TrimMaterial resolveTrimMaterial(String rawName) {
        NamespacedKey key = registryKey(rawName);
        if (key == null) {
            return null;
        }
        if (SnVersion.supports(20, 6)) {
            try {
                TrimMaterial modern = RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.TRIM_MATERIAL).get(key);
                if (modern != null) {
                    return modern;
                }
            } catch (Throwable registryAccessUnavailable) {
                // Server sin RegistryAccess pese a la version: cae al campo legacy.
            }
        }
        return legacyTrimMaterial(key);
    }

    @SuppressWarnings("deprecation") // Registry.TRIM_* deprecado desde 1.20.6; fallback 1.20.4.
    private static @Nullable TrimPattern legacyTrimPattern(NamespacedKey key) {
        return Registry.TRIM_PATTERN.get(key);
    }

    @SuppressWarnings("deprecation") // Registry.TRIM_* deprecado desde 1.20.6; fallback 1.20.4.
    private static @Nullable TrimMaterial legacyTrimMaterial(NamespacedKey key) {
        return Registry.TRIM_MATERIAL.get(key);
    }

    private void applyPotionEffects(ItemMeta meta) {
        if (potionEffects.isEmpty()) {
            return;
        }
        if (!(meta instanceof PotionMeta potion)) {
            warnOnce("potion-meta:" + material, "potion-effects definidos para " + material
                    + " sin PotionMeta; se ignoran");
            return;
        }
        List<String> tokens = tokenize(potionEffects);
        int i = 0;
        while (i < tokens.size()) {
            String id = tokens.get(i++);
            if (isInt(id)) {
                warnOnce("potion-shape:" + id, "Valor numerico '" + id
                        + "' sin efecto previo; se ignora (formato: [efecto, nivel, duracion])");
                continue;
            }
            int level = 1;
            int duration = 200;
            if (i < tokens.size() && isInt(tokens.get(i))) {
                level = Integer.parseInt(tokens.get(i).trim());
                i++;
            }
            if (i < tokens.size() && isInt(tokens.get(i))) {
                duration = Integer.parseInt(tokens.get(i).trim());
                i++;
            }
            PotionEffectType type = resolveEffect(id);
            if (type == null) {
                warnOnce("effect:" + id, "Efecto de pocion invalido '" + id + "'; se ignora");
                continue;
            }
            potion.addCustomEffect(
                    new PotionEffect(type, Math.max(1, duration), Math.max(0, level - 1)), true);
        }
    }

    private void applyMaxStackSize(ItemMeta meta) {
        Method setter = SnCompat.probe(ItemMeta.class, "setMaxStackSize", Integer.class);
        if (setter == null) {
            // 1.20.4: el probe ya aviso con UN WARN; se omite el campo.
            return;
        }
        try {
            setter.invoke(meta, Integer.valueOf(Math.max(1, Math.min(99, maxStackSize))));
        } catch (ReflectiveOperationException e) {
            warnOnce("maxstack-invoke", "setMaxStackSize fallo (" + e + "); se omite");
        }
    }

    private void applyHead(ItemMeta meta) {
        if (meta instanceof SkullMeta skull) {
            HeadUtil.applyBase64(skull, headBase64);
        } else {
            warnOnce("head-meta:" + material, "headBase64 requiere PLAYER_HEAD; material actual "
                    + material + "; se ignora");
        }
    }

    private void validateEquipmentSlot() {
        if (equipmentSlot == null || equipmentSlot.isBlank()) {
            return;
        }
        if (parseEquipmentSlot(equipmentSlot) == null) {
            warnOnce("slot:" + equipmentSlot, "equipment-slot invalido '" + equipmentSlot
                    + "' (se espera MAINHAND, OFFHAND, HEAD, CHEST, LEGS o FEET)");
        }
    }

    private static Material resolveMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.STONE;
        }
        Material match = Material.matchMaterial(raw.trim());
        if (match != null) {
            return match;
        }
        NamespacedKey key = registryKey(raw);
        if (key != null) {
            Material byKey = Registry.MATERIAL.get(key);
            if (byKey != null) {
                return byKey;
            }
        }
        warnOnce("material:" + raw, "Material invalido '" + raw + "'; usando STONE");
        return Material.STONE;
    }

    @SuppressWarnings("deprecation") // getByName resuelve nombres legacy pre-Registry.
    private static @Nullable Enchantment resolveEnchant(String id) {
        NamespacedKey key = registryKey(id);
        if (key != null) {
            Enchantment byKey = Registry.ENCHANTMENT.get(key);
            if (byKey != null) {
                return byKey;
            }
        }
        return Enchantment.getByName(id.trim().toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings("deprecation") // getByName resuelve nombres legacy (FAST_DIGGING).
    private static @Nullable PotionEffectType resolveEffect(String id) {
        NamespacedKey key = registryKey(id);
        if (key != null) {
            PotionEffectType byKey = Registry.EFFECT.get(key);
            if (byKey != null) {
                return byKey;
            }
        }
        return PotionEffectType.getByName(id.trim().toUpperCase(Locale.ROOT));
    }

    private static @Nullable NamespacedKey registryKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    /** Splits list entries on whitespace/commas so flat and inline spec shapes both parse. */
    private static List<String> tokenize(List<String> raw) {
        List<String> tokens = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            for (String token : entry.split("[\\s,;]+")) {
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private static boolean isInt(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
