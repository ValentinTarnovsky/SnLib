package com.sn.lib.item;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
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
 * {@code HIDE_POTION_EFFECTS}/{@code HIDE_ADDITIONAL_TOOLTIP} alias, never switch/EnumSet.
 * {@link AttributeModifier} creation is dual-branch: the {@link NamespacedKey} constructor
 * with {@link EquipmentSlotGroup} on 1.21+ (gated by {@link SnVersion#supports(int, int)}
 * plus a Throwable catch, because {@link SnCompat#probe} covers methods, not constructors)
 * and the deprecated UUID constructor with a deterministic name-derived UUID on 1.20.4.</p>
 *
 * <p>Covered fields: display-name, material (with head texture convention), skull-owner,
 * custom-model-data, amount, glow, lore, enchantments, flags, color, trim, potion-effects,
 * attributes, damage, unbreakable, max-stack-size and equipment-slot.</p>
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
    private String skullOwner;
    private final List<AttributeLine> attributes = new ArrayList<>();
    private Integer vanillaDamage;
    private Boolean unbreakable;
    private Integer maxStackSize;
    private String equipmentSlot;

    /** One declared attribute modifier line; static definition values, no placeholders. */
    private record AttributeLine(String attribute, String operation, double amount,
                                 @Nullable String slotGroup) {
    }

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
     * trim-pattern/trim-material, potion-effects, unbreakable, max-stack-size,
     * equipment-slot, skull-owner (placeholder-resolved per viewer), attributes (static
     * definition values, no placeholders) and damage (only when set). Strings resolve
     * through the yml pipeline with {@code viewer} plus the extra local placeholders
     * {@code phs}.
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
        String owner = SnText.applyLocals(yml.getString(p + "skull-owner", "", viewer), phs);
        if (!owner.isEmpty()) {
            item.skullOwner(owner);
        }
        readAttributes(item, yml.getStringList(p + "attributes", List.of()));
        if (yml.isSet(p + "damage")) {
            item.damage(yml.getInt(p + "damage", 0));
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

    /**
     * Head owner by player name or UUID; requires PLAYER_HEAD. A UUID resolves via
     * {@code Bukkit.getOfflinePlayer(UUID)} (non-blocking) and a name only via
     * {@code Bukkit.getOfflinePlayerIfCached} (never the blocking string lookup); an
     * uncached name leaves the default head with one WARN. Takes precedence over
     * {@link #headBase64} when both are set. Null or blank is a no-op.
     */
    public SnItem skullOwner(String nameOrUuid) {
        if (nameOrUuid != null && !nameOrUuid.isBlank()) {
            this.skullOwner = nameOrUuid.trim();
        }
        return this;
    }

    /**
     * Adds an attribute modifier line. Attribute ids resolve leniently across the
     * 1.21.2+ rename (bidirectional {@code GENERIC_ARMOR} / {@code ARMOR} alias); the
     * operation is an {@link AttributeModifier.Operation} name; {@code slotGroup} is an
     * {@link EquipmentSlotGroup} name (null or blank means ANY). Null or blank
     * {@code attributeId} or {@code operation} is a no-op.
     */
    public SnItem attribute(String attributeId, String operation, double amount,
                            @Nullable String slotGroup) {
        if (attributeId == null || attributeId.isBlank()
                || operation == null || operation.isBlank()) {
            return this;
        }
        attributes.add(new AttributeLine(attributeId.trim(), operation.trim(), amount,
                slotGroup == null || slotGroup.isBlank() ? null : slotGroup.trim()));
        return this;
    }

    /**
     * Initial VANILLA durability already spent, clamped to [0, max durability] at build.
     * Independent from the custom-durability system of the item definition layer.
     */
    public SnItem damage(int damage) {
        this.vanillaDamage = damage;
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
        applyAttributes(meta);
        if (modelData != null) {
            meta.setCustomModelData(modelData);
        }
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (maxStackSize != null) {
            applyMaxStackSize(meta);
        }
        if (vanillaDamage != null) {
            applyDamage(meta);
        }
        if (skullOwner != null) {
            if (headBase64 != null) {
                warnOnce("skull-owner-conflict:" + material, "skull-owner and base64 texture "
                        + "defined at the same time for " + material
                        + "; skull-owner wins and the base64 texture is ignored");
            }
            applySkullOwner(meta);
        } else if (headBase64 != null) {
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
                warnOnce("ench-shape:" + id, "Enchantment level '" + id
                        + "' without a preceding id; ignored (expected format: [id, level, ...])");
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

    /**
     * Walks the {@code attributes:} list; each line is {@code ATTRIBUTE OPERATION amount
     * [slot-group]} tokenized on whitespace. Lines never go through placeholders: they
     * are static definition values. Fewer than 3 tokens or an unparseable amount skip the
     * line with one WARN.
     */
    private static void readAttributes(SnItem item, List<String> raw) {
        for (String line : raw) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            Double amount = tokens.length < 3 ? null : parseDouble(tokens[2]);
            if (amount == null) {
                warnOnce("attr-line:" + line, "Invalid attribute line '" + line
                        + "' (format: ATTRIBUTE OPERATION amount [slot-group]); ignored");
                continue;
            }
            item.attribute(tokens[0], tokens[1], amount, tokens.length >= 4 ? tokens[3] : null);
        }
    }

    private static @Nullable Double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void applyEnchants(ItemMeta meta) {
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            Enchantment enchantment = resolveEnchant(entry.getKey());
            if (enchantment == null) {
                warnOnce("ench:" + entry.getKey(), "Invalid enchantment '" + entry.getKey()
                        + "': not resolved by Registry nor by legacy name; ignored");
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
                warnOnce("glint-invoke", "setEnchantmentGlintOverride failed (" + e
                        + "); degrading to a real enchantment + HIDE_ENCHANTS");
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
                // Name from another version branch; skipped without a WARN.
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
                            + " does not exist on this server; aliased to " + alias);
                    return aliased;
                } catch (IllegalArgumentException aliasAlsoUnknown) {
                    // Falls through to the generic WARN.
                }
            }
            warnOnce("flag:" + flagName, "Invalid ItemFlag '" + flagName + "'; ignored");
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
            warnOnce("color:" + color, "Invalid color '" + color
                    + "' (expected 'R, G, B' or hex 'RRGGBB'); ignored");
            return;
        }
        if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(parsed);
        } else if (meta instanceof PotionMeta potion) {
            potion.setColor(parsed);
        } else {
            warnOnce("color-meta:" + material, "color defined for " + material
                    + " which does not support tinting; ignored");
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
            // Malformed number or channel outside 0-255; the caller warns with ONE WARN.
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
            warnOnce("trim-meta:" + material, "trim defined for " + material
                    + " which is not armour; ignored");
            return;
        }
        if (!hasPattern || !hasMaterial) {
            warnOnce("trim-pair:" + trimPattern + "/" + trimMaterial,
                    "trim-pattern and trim-material must be defined together; ignored");
            return;
        }
        TrimPattern pattern = resolveTrimPattern(trimPattern);
        if (pattern == null) {
            warnOnce("trim-pattern:" + trimPattern, "Invalid trim-pattern '" + trimPattern
                    + "'; ignored");
            return;
        }
        TrimMaterial trimMat = resolveTrimMaterial(trimMaterial);
        if (trimMat == null) {
            warnOnce("trim-material:" + trimMaterial, "Invalid trim-material '" + trimMaterial
                    + "'; ignored");
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
                // Server without RegistryAccess despite the version: falls to the legacy field.
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
                // Server without RegistryAccess despite the version: falls to the legacy field.
            }
        }
        return legacyTrimMaterial(key);
    }

    @SuppressWarnings("deprecation") // Registry.TRIM_* deprecated since 1.20.6; 1.20.4 fallback.
    private static @Nullable TrimPattern legacyTrimPattern(NamespacedKey key) {
        return Registry.TRIM_PATTERN.get(key);
    }

    @SuppressWarnings("deprecation") // Registry.TRIM_* deprecated since 1.20.6; 1.20.4 fallback.
    private static @Nullable TrimMaterial legacyTrimMaterial(NamespacedKey key) {
        return Registry.TRIM_MATERIAL.get(key);
    }

    private void applyPotionEffects(ItemMeta meta) {
        if (potionEffects.isEmpty()) {
            return;
        }
        if (!(meta instanceof PotionMeta potion)) {
            warnOnce("potion-meta:" + material, "potion-effects defined for " + material
                    + " without PotionMeta; ignored");
            return;
        }
        List<String> tokens = tokenize(potionEffects);
        int i = 0;
        while (i < tokens.size()) {
            String id = tokens.get(i++);
            if (isInt(id)) {
                warnOnce("potion-shape:" + id, "Numeric value '" + id
                        + "' without a preceding effect; ignored (format: [effect, level, duration])");
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
                warnOnce("effect:" + id, "Invalid potion effect '" + id + "'; ignored");
                continue;
            }
            potion.addCustomEffect(
                    new PotionEffect(type, Math.max(1, duration), Math.max(0, level - 1)), true);
        }
    }

    private void applyAttributes(ItemMeta meta) {
        for (int i = 0; i < attributes.size(); i++) {
            AttributeLine line = attributes.get(i);
            Attribute attribute = resolveAttribute(line.attribute());
            if (attribute == null) {
                warnOnce("attribute:" + line.attribute(), "Invalid attribute '"
                        + line.attribute()
                        + "': not resolved by Registry in any of its forms; ignored");
                continue;
            }
            AttributeModifier.Operation operation = parseOperation(line.operation());
            if (operation == null) {
                continue;
            }
            String keyName = "attr_" + i + "_" + sanitizeKeyPart(line.attribute());
            meta.addAttributeModifier(attribute,
                    buildModifier(keyName, line.amount(), operation, line.slotGroup()));
        }
    }

    /**
     * Lenient attribute lookup: tries every candidate of {@link #attributeKeyCandidates}
     * against {@code Registry.ATTRIBUTE}; first hit wins, all misses yield null. Covers
     * the bidirectional {@code GENERIC_ARMOR} / {@code ARMOR} alias of the 1.21.2+ rename
     * without hardcoded tables.
     */
    private static @Nullable Attribute resolveAttribute(String raw) {
        for (String candidate : attributeKeyCandidates(raw)) {
            NamespacedKey key = NamespacedKey.fromString(candidate);
            if (key == null) {
                continue;
            }
            Attribute attribute = Registry.ATTRIBUTE.get(key);
            if (attribute != null) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Ordered, duplicate-free registry key candidates for a raw attribute id. Normalizes
     * (trim, lowercase, {@code '-'} to {@code '_'}, drops a {@code minecraft:} prefix) and
     * emits: the normalized form, the form without a {@code generic_}/{@code player_}/
     * {@code zombie_} prefix (1.21.2+ keys), the dotted pre-1.21.3 form when prefixed
     * ({@code generic.movement_speed}), and the inverse {@code generic.} alias for bare
     * modern names ({@code ARMOR} resolves as {@code generic.armor} on older servers).
     */
    static List<String> attributeKeyCandidates(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        String stripped = normalized;
        boolean prefixed = false;
        for (String prefix : new String[] {"generic_", "player_", "zombie_"}) {
            if (normalized.startsWith(prefix)) {
                stripped = normalized.substring(prefix.length());
                prefixed = true;
                candidates.add(stripped);
                break;
            }
        }
        if (prefixed) {
            candidates.add(normalized.replaceFirst("_", "."));
        }
        if (stripped.indexOf('.') < 0) {
            candidates.add("generic." + stripped);
        }
        return List.copyOf(candidates);
    }

    private static @Nullable AttributeModifier.Operation parseOperation(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return AttributeModifier.Operation.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            warnOnce("attr-op:" + raw, "Invalid attribute operation '" + raw
                    + "' (expected ADD_NUMBER, ADD_SCALAR or MULTIPLY_SCALAR_1); ignored");
            return null;
        }
    }

    /**
     * Dual-branch modifier construction. Modern branch (NamespacedKey constructor with
     * {@link EquipmentSlotGroup}) gated by {@code SnVersion.supports(21, 0)} and wrapped
     * in a Throwable catch: {@code SnCompat.probe} only covers methods, not constructors.
     * The key is deterministic ({@code snlib:} namespace via {@code
     * NamespacedKey.fromString}, no plugin reference needed). Legacy branch (1.20.4 or
     * modern failure) uses the deprecated UUID constructor.
     */
    private static AttributeModifier buildModifier(String keyName, double amount,
            AttributeModifier.Operation operation, @Nullable String slotGroup) {
        if (SnVersion.supports(21, 0)) {
            try {
                return new AttributeModifier(NamespacedKey.fromString("snlib:" + keyName),
                        amount, operation, resolveSlotGroup(slotGroup));
            } catch (Throwable modernUnavailable) {
                // Constructor or EquipmentSlotGroup absent despite the version: legacy branch.
            }
        }
        return legacyModifier(keyName, amount, operation, slotGroup);
    }

    private static EquipmentSlotGroup resolveSlotGroup(@Nullable String slotGroup) {
        if (slotGroup == null || slotGroup.isBlank()) {
            return EquipmentSlotGroup.ANY;
        }
        EquipmentSlotGroup group =
                EquipmentSlotGroup.getByName(slotGroup.trim().toLowerCase(Locale.ROOT));
        if (group == null) {
            warnOnce("attr-slot:" + slotGroup, "Invalid attribute slot-group '" + slotGroup
                    + "'; using ANY");
            return EquipmentSlotGroup.ANY;
        }
        return group;
    }

    @SuppressWarnings("deprecation") // Pre-NamespacedKey UUID constructor; 1.20.4 fallback.
    private static AttributeModifier legacyModifier(String keyName, double amount,
            AttributeModifier.Operation operation, @Nullable String slotGroup) {
        UUID id = UUID.nameUUIDFromBytes(keyName.getBytes(StandardCharsets.UTF_8));
        EquipmentSlot slot = legacySlot(slotGroup);
        return slot == null ? new AttributeModifier(id, keyName, amount, operation)
                : new AttributeModifier(id, keyName, amount, operation, slot);
    }

    /**
     * Legacy slot-group to single {@link EquipmentSlot}: null, blank, {@code ANY},
     * {@code ARMOR} and {@code BODY} have no single-slot equivalent and yield null (the
     * modifier applies to every slot); the rest delegates to {@link #parseEquipmentSlot}
     * with one WARN and null on unknown names.
     */
    static @Nullable EquipmentSlot legacySlot(@Nullable String slotGroup) {
        if (slotGroup == null || slotGroup.isBlank()) {
            return null;
        }
        String name = slotGroup.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (name.equals("ANY") || name.equals("ARMOR") || name.equals("BODY")) {
            return null;
        }
        EquipmentSlot slot = parseEquipmentSlot(slotGroup);
        if (slot == null) {
            warnOnce("attr-slot:" + slotGroup, "Invalid attribute slot-group '" + slotGroup
                    + "'; applied to every slot");
        }
        return slot;
    }

    /** Lowercases and maps every char outside {@code [a-z0-9._-]} to {@code '_'}. */
    private static String sanitizeKeyPart(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            out.append(valid ? c : '_');
        }
        return out.toString();
    }

    /**
     * Initial vanilla damage, clamped to [0, max durability]. Materials without vanilla
     * durability or metas that are not {@link Damageable} skip the field with one WARN.
     */
    private void applyDamage(ItemMeta meta) {
        if (material.getMaxDurability() <= 0 || !(meta instanceof Damageable damageable)) {
            warnOnce("damage-meta:" + material, "damage defined for " + material
                    + " without vanilla durability; ignored");
            return;
        }
        damageable.setDamage(Math.max(0, Math.min(vanillaDamage, material.getMaxDurability())));
    }

    private void applyMaxStackSize(ItemMeta meta) {
        Method setter = SnCompat.probe(ItemMeta.class, "setMaxStackSize", Integer.class);
        if (setter == null) {
            // 1.20.4: the probe already warned with ONE WARN; the field is skipped.
            return;
        }
        try {
            setter.invoke(meta, Integer.valueOf(Math.max(1, Math.min(99, maxStackSize))));
        } catch (ReflectiveOperationException e) {
            warnOnce("maxstack-invoke", "setMaxStackSize failed (" + e + "); skipped");
        }
    }

    private void applyHead(ItemMeta meta) {
        if (meta instanceof SkullMeta skull) {
            HeadUtil.applyBase64(skull, headBase64);
        } else {
            warnOnce("head-meta:" + material, "headBase64 requires PLAYER_HEAD; current material "
                    + material + "; ignored");
        }
    }

    private void applySkullOwner(ItemMeta meta) {
        if (!(meta instanceof SkullMeta skull)) {
            warnOnce("skull-owner-meta:" + material, "skull-owner requires PLAYER_HEAD; "
                    + "current material " + material + "; ignored");
            return;
        }
        OfflinePlayer resolved = resolveSkullOwner(skullOwner);
        if (resolved == null) {
            warnOnce("skull-owner:" + skullOwner, "skull-owner '" + skullOwner
                    + "' is neither a UUID nor a name cached on this server; "
                    + "keeping the default head");
            return;
        }
        HeadUtil.applyOwner(skull, resolved);
    }

    /**
     * UUID first via non-blocking {@code Bukkit.getOfflinePlayer(UUID)}; otherwise the
     * profile cache via {@code Bukkit.getOfflinePlayerIfCached}. Never the blocking
     * name-based lookup: it can hit HTTP on the main thread. Cache miss yields null.
     */
    private static @Nullable OfflinePlayer resolveSkullOwner(String raw) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(raw));
        } catch (IllegalArgumentException notAUuid) {
            return Bukkit.getOfflinePlayerIfCached(raw);
        }
    }

    private void validateEquipmentSlot() {
        if (equipmentSlot == null || equipmentSlot.isBlank()) {
            return;
        }
        if (parseEquipmentSlot(equipmentSlot) == null) {
            warnOnce("slot:" + equipmentSlot, "Invalid equipment-slot '" + equipmentSlot
                    + "' (expected MAINHAND, OFFHAND, HEAD, CHEST, LEGS or FEET)");
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
        warnOnce("material:" + raw, "Invalid material '" + raw + "'; using STONE");
        return Material.STONE;
    }

    @SuppressWarnings("deprecation") // getByName resolves legacy pre-Registry names.
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
