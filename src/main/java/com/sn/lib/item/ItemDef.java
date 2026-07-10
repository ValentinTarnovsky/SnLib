package com.sn.lib.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.action.Requirement;
import com.sn.lib.action.RequirementEngine;
import com.sn.lib.yml.SnYml;

/**
 * Immutable definition of a physical item covering the full golden spec
 * ({@code docs/item-example.yml}): appearance, behaviour properties (droppable, moveable,
 * placeable, tradeable, despawnable, keep-on-death, cooldown), locked-mode fields
 * (locked, no-drop, no-manual-equip, obtain-via), custom durability, the eight interact
 * action lists with their Java callbacks, interact requirements with deny actions,
 * pickup/drop actions, held effects, equipment slot and recipe.
 *
 * <p>{@link #builder()} is a first-class universal constructor: every field can be set
 * programmatically with no YML file, including a {@code BiConsumer} callback per interact
 * variant. YML-backed definitions are created through
 * {@link ItemRegistry#register(String, SnYml)}; their appearance section is re-read on
 * every {@link ItemRegistry#create}, so appearance placeholders resolve per viewer.</p>
 *
 * <p>{@code max-stack-size} belongs to the appearance layer ({@link SnItem#maxStackSize});
 * it is not duplicated here. Interact requirements are parsed once at construction into an
 * immutable {@link Requirement} tree.</p>
 */
public final class ItemDef {

    private final @Nullable SnYml yml;
    private final @Nullable String path;
    private final @Nullable SnItem appearance;
    private final @Nullable ItemStack template;

    private final boolean droppable;
    private final boolean moveable;
    private final boolean placeable;
    private final boolean tradeable;
    private final boolean despawnable;
    private final boolean keepOnDeath;
    private final int cooldownTicks;

    private final boolean locked;
    private final boolean noDrop;
    private final boolean noManualEquip;
    private final ObtainMode obtainVia;

    private final int durabilityMax;
    private final int durabilityDamagePerUse;
    private final String durabilityLoreFormat;
    private final List<String> breakActions;

    private final List<String> rightClickActions;
    private final List<String> leftClickActions;
    private final List<String> shiftRightClickActions;
    private final List<String> shiftLeftClickActions;
    private final List<String> rightClickBlockActions;
    private final List<String> rightClickAirActions;
    private final List<String> leftClickBlockActions;
    private final List<String> leftClickAirActions;

    private final List<String> interactRequirements;
    private final Requirement interactRequirement;
    private final List<String> denyActions;
    private final List<String> pickupActions;
    private final List<String> dropActions;

    private final List<String> heldEffectsMainhand;
    private final List<String> heldEffectsOffhand;
    private final List<String> heldEffectsArmor;

    private final String equipmentSlotName;
    private final @Nullable EquipmentSlot equipmentSlot;

    private final @Nullable Recipe recipe;

    private final @Nullable BiConsumer<Player, ItemStack> onRightClick;
    private final @Nullable BiConsumer<Player, ItemStack> onLeftClick;
    private final @Nullable BiConsumer<Player, ItemStack> onShiftRightClick;
    private final @Nullable BiConsumer<Player, ItemStack> onShiftLeftClick;
    private final @Nullable BiConsumer<Player, ItemStack> onRightClickBlock;
    private final @Nullable BiConsumer<Player, ItemStack> onRightClickAir;
    private final @Nullable BiConsumer<Player, ItemStack> onLeftClickBlock;
    private final @Nullable BiConsumer<Player, ItemStack> onLeftClickAir;

    private ItemDef(Builder b) {
        this.yml = b.yml;
        this.path = b.path;
        this.appearance = b.appearance;
        this.template = b.template == null ? null : b.template.clone();
        this.droppable = b.droppable;
        this.moveable = b.moveable;
        this.placeable = b.placeable;
        this.tradeable = b.tradeable;
        this.despawnable = b.despawnable;
        this.keepOnDeath = b.keepOnDeath;
        this.cooldownTicks = Math.max(0, b.cooldownTicks);
        this.locked = b.locked;
        this.noDrop = b.noDrop;
        this.noManualEquip = b.noManualEquip;
        this.obtainVia = b.obtainVia;
        this.durabilityMax = Math.max(0, b.durabilityMax);
        this.durabilityDamagePerUse = Math.max(1, b.durabilityDamagePerUse);
        this.durabilityLoreFormat = b.durabilityLoreFormat == null ? "" : b.durabilityLoreFormat;
        this.breakActions = copy(b.breakActions);
        this.rightClickActions = copy(b.rightClickActions);
        this.leftClickActions = copy(b.leftClickActions);
        this.shiftRightClickActions = copy(b.shiftRightClickActions);
        this.shiftLeftClickActions = copy(b.shiftLeftClickActions);
        this.rightClickBlockActions = copy(b.rightClickBlockActions);
        this.rightClickAirActions = copy(b.rightClickAirActions);
        this.leftClickBlockActions = copy(b.leftClickBlockActions);
        this.leftClickAirActions = copy(b.leftClickAirActions);
        this.interactRequirements = copy(b.interactRequirements);
        this.interactRequirement = RequirementEngine.parse(this.interactRequirements);
        this.denyActions = copy(b.denyActions);
        this.pickupActions = copy(b.pickupActions);
        this.dropActions = copy(b.dropActions);
        this.heldEffectsMainhand = copy(b.heldEffectsMainhand);
        this.heldEffectsOffhand = copy(b.heldEffectsOffhand);
        this.heldEffectsArmor = copy(b.heldEffectsArmor);
        this.equipmentSlotName = b.equipmentSlotName == null ? "" : b.equipmentSlotName.trim();
        this.equipmentSlot = SnItem.parseEquipmentSlot(this.equipmentSlotName);
        this.recipe = b.recipe;
        this.onRightClick = b.onRightClick;
        this.onLeftClick = b.onLeftClick;
        this.onShiftRightClick = b.onShiftRightClick;
        this.onShiftLeftClick = b.onShiftLeftClick;
        this.onRightClickBlock = b.onRightClickBlock;
        this.onRightClickAir = b.onRightClickAir;
        this.onLeftClickBlock = b.onLeftClickBlock;
        this.onLeftClickAir = b.onLeftClickAir;
    }

    /** Starts the universal programmatic builder; no YML file is required. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses a full definition from the section at {@code path} inside {@code yml};
     * warnings go to {@code warn}. Returns null when the section does not exist.
     */
    static @Nullable ItemDef fromYml(SnYml yml, String path, Consumer<String> warn) {
        ConfigurationSection sec = yml.getSection(path);
        if (sec == null) {
            warn.accept("No existe la seccion '" + path + "' en " + yml.file().getName()
                    + "; item ignorado");
            return null;
        }
        Builder b = builder();
        b.yml = yml;
        b.path = path;
        b.droppable = sec.getBoolean("droppable", true);
        b.moveable = sec.getBoolean("moveable", true);
        b.placeable = sec.getBoolean("placeable", true);
        b.tradeable = sec.getBoolean("tradeable", true);
        b.despawnable = sec.getBoolean("despawnable", true);
        b.keepOnDeath = sec.getBoolean("keep-on-death", false);
        b.cooldownTicks = sec.getInt("cooldown", 0);
        b.locked = sec.getBoolean("locked", false);
        b.noDrop = sec.getBoolean("no-drop", false);
        b.noManualEquip = sec.getBoolean("no-manual-equip", false);
        b.obtainVia = ObtainMode.parse(sec.getString("obtain-via", ""), warn);
        ConfigurationSection durability = sec.getConfigurationSection("custom-durability");
        if (durability != null) {
            b.durabilityMax = durability.getInt("max", 0);
            b.durabilityDamagePerUse = durability.getInt("damage-per-use", 1);
            b.durabilityLoreFormat = durability.getString("lore-format", "");
            b.breakActions = durability.getStringList("break-actions");
        }
        b.rightClickActions = sec.getStringList("right-click-actions");
        b.leftClickActions = sec.getStringList("left-click-actions");
        b.shiftRightClickActions = sec.getStringList("shift-right-click-actions");
        b.shiftLeftClickActions = sec.getStringList("shift-left-click-actions");
        b.rightClickBlockActions = sec.getStringList("right-click-block-actions");
        b.rightClickAirActions = sec.getStringList("right-click-air-actions");
        b.leftClickBlockActions = sec.getStringList("left-click-block-actions");
        b.leftClickAirActions = sec.getStringList("left-click-air-actions");
        b.interactRequirements = sec.getStringList("interact-requirements");
        b.denyActions = sec.getStringList("deny-actions");
        b.pickupActions = sec.getStringList("pickup-actions");
        b.dropActions = sec.getStringList("drop-actions");
        ConfigurationSection held = sec.getConfigurationSection("held-effects");
        if (held != null) {
            b.heldEffectsMainhand = held.getStringList("mainhand");
            b.heldEffectsOffhand = held.getStringList("offhand");
            b.heldEffectsArmor = held.getStringList("armor");
        }
        b.equipmentSlotName = sec.getString("equipment-slot", "");
        ConfigurationSection recipeSection = sec.getConfigurationSection("recipe");
        if (recipeSection != null) {
            b.recipe = Recipe.fromSection(recipeSection, warn);
        }
        return b.build();
    }

    /**
     * Builds the physical stack for this definition, without the registry id tag. YML
     * definitions re-read their appearance section with {@code viewer} and {@code phs};
     * programmatic definitions render their captured {@link SnItem} or clone their
     * template stack.
     */
    ItemStack buildStack(@Nullable Player viewer, Ph... phs) {
        if (yml != null && path != null) {
            return SnItem.fromConfig(yml, path, viewer, phs).build();
        }
        if (appearance != null) {
            return appearance.build();
        }
        if (template != null) {
            return template.clone();
        }
        return new ItemStack(Material.STONE);
    }

    /** Whether the player can drop the item. */
    public boolean droppable() {
        return droppable;
    }

    /** Whether the item can be moved in inventories. */
    public boolean moveable() {
        return moveable;
    }

    /** Whether the item can be placed as a block. */
    public boolean placeable() {
        return placeable;
    }

    /** Whether the item can be traded with villagers. */
    public boolean tradeable() {
        return tradeable;
    }

    /** Whether the item despawns when dropped on the ground. */
    public boolean despawnable() {
        return despawnable;
    }

    /** Whether the item is kept on death and returned on respawn. */
    public boolean keepOnDeath() {
        return keepOnDeath;
    }

    /** Cooldown between interactions in ticks; 0 disables it. */
    public int cooldownTicks() {
        return cooldownTicks;
    }

    /** Whether the item is pinned to its slot (locked mode). */
    public boolean locked() {
        return locked;
    }

    /** Hard alias of {@code droppable: false}; blocks drop and drag-out attempts. */
    public boolean noDrop() {
        return noDrop;
    }

    /** Whether manual equipping into armor or offhand slots is blocked. */
    public boolean noManualEquip() {
        return noManualEquip;
    }

    /** How the item may legitimately enter circulation. */
    public ObtainMode obtainVia() {
        return obtainVia;
    }

    /** Custom durability maximum; 0 disables the custom durability system. */
    public int durabilityMax() {
        return durabilityMax;
    }

    /** Durability lost per use; floored at 1. */
    public int durabilityDamagePerUse() {
        return durabilityDamagePerUse;
    }

    /** Lore line format with {@code %durability%}/{@code %max_durability%}; empty hides it. */
    public String durabilityLoreFormat() {
        return durabilityLoreFormat;
    }

    /** Action lines run when the custom durability reaches 0. */
    public List<String> breakActions() {
        return breakActions;
    }

    /** Action lines for a right click. */
    public List<String> rightClickActions() {
        return rightClickActions;
    }

    /** Action lines for a left click. */
    public List<String> leftClickActions() {
        return leftClickActions;
    }

    /** Action lines for a shift right click. */
    public List<String> shiftRightClickActions() {
        return shiftRightClickActions;
    }

    /** Action lines for a shift left click. */
    public List<String> shiftLeftClickActions() {
        return shiftLeftClickActions;
    }

    /** Action lines for a right click on a block. */
    public List<String> rightClickBlockActions() {
        return rightClickBlockActions;
    }

    /** Action lines for a right click in the air. */
    public List<String> rightClickAirActions() {
        return rightClickAirActions;
    }

    /** Action lines for a left click on a block. */
    public List<String> leftClickBlockActions() {
        return leftClickBlockActions;
    }

    /** Action lines for a left click in the air. */
    public List<String> leftClickAirActions() {
        return leftClickAirActions;
    }

    /** Raw interact requirement lines as declared. */
    public List<String> interactRequirements() {
        return interactRequirements;
    }

    /** Requirement tree parsed once from {@link #interactRequirements()}; never null. */
    public Requirement interactRequirement() {
        return interactRequirement;
    }

    /** Action lines run when the interact requirements are not met. */
    public List<String> denyActions() {
        return denyActions;
    }

    /** Action lines run when a player picks up the item. */
    public List<String> pickupActions() {
        return pickupActions;
    }

    /** Action lines run when a player drops the item. */
    public List<String> dropActions() {
        return dropActions;
    }

    /** Held effect lines ({@code "EFFECT amplifier"}) applied while in main hand. */
    public List<String> heldEffectsMainhand() {
        return heldEffectsMainhand;
    }

    /** Held effect lines applied while in offhand. */
    public List<String> heldEffectsOffhand() {
        return heldEffectsOffhand;
    }

    /** Held effect lines applied while worn as armor. */
    public List<String> heldEffectsArmor() {
        return heldEffectsArmor;
    }

    /** Declared equipment slot name of the spec; empty allows any slot. */
    public String equipmentSlotName() {
        return equipmentSlotName;
    }

    /** Parsed equipment slot, or null when unrestricted or the name was invalid. */
    public @Nullable EquipmentSlot equipmentSlot() {
        return equipmentSlot;
    }

    /** Crafting recipe of the item, or null when it has none. */
    public @Nullable Recipe recipe() {
        return recipe;
    }

    /** Java callback for a right click, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onRightClick() {
        return onRightClick;
    }

    /** Java callback for a left click, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onLeftClick() {
        return onLeftClick;
    }

    /** Java callback for a shift right click, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onShiftRightClick() {
        return onShiftRightClick;
    }

    /** Java callback for a shift left click, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onShiftLeftClick() {
        return onShiftLeftClick;
    }

    /** Java callback for a right click on a block, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onRightClickBlock() {
        return onRightClickBlock;
    }

    /** Java callback for a right click in the air, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onRightClickAir() {
        return onRightClickAir;
    }

    /** Java callback for a left click on a block, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onLeftClickBlock() {
        return onLeftClickBlock;
    }

    /** Java callback for a left click in the air, or null. */
    public @Nullable BiConsumer<Player, ItemStack> onLeftClickAir() {
        return onLeftClickAir;
    }

    private static List<String> copy(@Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (String value : list) {
            if (value != null) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Universal builder: every spec field is settable programmatically. The appearance
     * comes from either a captured {@link SnItem} (rendered on every create) or a fixed
     * {@link ItemStack} template (cloned on every create).
     */
    public static final class Builder {

        private SnYml yml;
        private String path;
        private SnItem appearance;
        private ItemStack template;
        private boolean droppable = true;
        private boolean moveable = true;
        private boolean placeable = true;
        private boolean tradeable = true;
        private boolean despawnable = true;
        private boolean keepOnDeath;
        private int cooldownTicks;
        private boolean locked;
        private boolean noDrop;
        private boolean noManualEquip;
        private ObtainMode obtainVia = ObtainMode.UNRESTRICTED;
        private int durabilityMax;
        private int durabilityDamagePerUse = 1;
        private String durabilityLoreFormat = "";
        private List<String> breakActions;
        private List<String> rightClickActions;
        private List<String> leftClickActions;
        private List<String> shiftRightClickActions;
        private List<String> shiftLeftClickActions;
        private List<String> rightClickBlockActions;
        private List<String> rightClickAirActions;
        private List<String> leftClickBlockActions;
        private List<String> leftClickAirActions;
        private List<String> interactRequirements;
        private List<String> denyActions;
        private List<String> pickupActions;
        private List<String> dropActions;
        private List<String> heldEffectsMainhand;
        private List<String> heldEffectsOffhand;
        private List<String> heldEffectsArmor;
        private String equipmentSlotName = "";
        private Recipe recipe;
        private BiConsumer<Player, ItemStack> onRightClick;
        private BiConsumer<Player, ItemStack> onLeftClick;
        private BiConsumer<Player, ItemStack> onShiftRightClick;
        private BiConsumer<Player, ItemStack> onShiftLeftClick;
        private BiConsumer<Player, ItemStack> onRightClickBlock;
        private BiConsumer<Player, ItemStack> onRightClickAir;
        private BiConsumer<Player, ItemStack> onLeftClickBlock;
        private BiConsumer<Player, ItemStack> onLeftClickAir;

        private Builder() {
        }

        /** Appearance from an {@link SnItem} builder, rendered fresh on every create. */
        public Builder item(SnItem item) {
            this.appearance = item;
            this.template = null;
            return this;
        }

        /** Appearance from a fixed stack, cloned on every create. */
        public Builder item(ItemStack stack) {
            this.template = stack;
            this.appearance = null;
            return this;
        }

        /** Whether the player can drop the item; default true. */
        public Builder droppable(boolean droppable) {
            this.droppable = droppable;
            return this;
        }

        /** Whether the item can be moved in inventories; default true. */
        public Builder moveable(boolean moveable) {
            this.moveable = moveable;
            return this;
        }

        /** Whether the item can be placed as a block; default true. */
        public Builder placeable(boolean placeable) {
            this.placeable = placeable;
            return this;
        }

        /** Whether the item can be traded with villagers; default true. */
        public Builder tradeable(boolean tradeable) {
            this.tradeable = tradeable;
            return this;
        }

        /** Whether the item despawns when dropped on the ground; default true. */
        public Builder despawnable(boolean despawnable) {
            this.despawnable = despawnable;
            return this;
        }

        /** Whether the item is kept on death and returned on respawn; default false. */
        public Builder keepOnDeath(boolean keepOnDeath) {
            this.keepOnDeath = keepOnDeath;
            return this;
        }

        /** Cooldown between interactions in ticks; default 0 (disabled). */
        public Builder cooldownTicks(int cooldownTicks) {
            this.cooldownTicks = cooldownTicks;
            return this;
        }

        /**
         * Custom durability separate from vanilla: {@code max} 0 disables it,
         * {@code loreFormat} renders {@code %durability%}/{@code %max_durability%} and
         * {@code breakActions} run when it reaches 0.
         */
        public Builder customDurability(int max, int damagePerUse, String loreFormat,
                List<String> breakActions) {
            this.durabilityMax = max;
            this.durabilityDamagePerUse = damagePerUse;
            this.durabilityLoreFormat = loreFormat;
            this.breakActions = breakActions;
            return this;
        }

        /** Action lines for a right click. */
        public Builder rightClickActions(List<String> actions) {
            this.rightClickActions = actions;
            return this;
        }

        /** Action lines for a left click. */
        public Builder leftClickActions(List<String> actions) {
            this.leftClickActions = actions;
            return this;
        }

        /** Action lines for a shift right click. */
        public Builder shiftRightClickActions(List<String> actions) {
            this.shiftRightClickActions = actions;
            return this;
        }

        /** Action lines for a shift left click. */
        public Builder shiftLeftClickActions(List<String> actions) {
            this.shiftLeftClickActions = actions;
            return this;
        }

        /** Action lines for a right click on a block. */
        public Builder rightClickBlockActions(List<String> actions) {
            this.rightClickBlockActions = actions;
            return this;
        }

        /** Action lines for a right click in the air. */
        public Builder rightClickAirActions(List<String> actions) {
            this.rightClickAirActions = actions;
            return this;
        }

        /** Action lines for a left click on a block. */
        public Builder leftClickBlockActions(List<String> actions) {
            this.leftClickBlockActions = actions;
            return this;
        }

        /** Action lines for a left click in the air. */
        public Builder leftClickAirActions(List<String> actions) {
            this.leftClickAirActions = actions;
            return this;
        }

        /** Requirement expressions checked before any interact action runs. */
        public Builder interactRequirements(List<String> requirements) {
            this.interactRequirements = requirements;
            return this;
        }

        /** Action lines run when the interact requirements are not met. */
        public Builder denyActions(List<String> actions) {
            this.denyActions = actions;
            return this;
        }

        /** Action lines run when a player picks up the item. */
        public Builder pickupActions(List<String> actions) {
            this.pickupActions = actions;
            return this;
        }

        /** Action lines run when a player drops the item. */
        public Builder dropActions(List<String> actions) {
            this.dropActions = actions;
            return this;
        }

        /** Effect lines ({@code "EFFECT amplifier"}) applied while in main hand. */
        public Builder heldEffectsMainhand(List<String> effects) {
            this.heldEffectsMainhand = effects;
            return this;
        }

        /** Effect lines applied while in offhand. */
        public Builder heldEffectsOffhand(List<String> effects) {
            this.heldEffectsOffhand = effects;
            return this;
        }

        /** Effect lines applied while worn as armor. */
        public Builder heldEffectsArmor(List<String> effects) {
            this.heldEffectsArmor = effects;
            return this;
        }

        /** Equipment slot restriction (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET). */
        public Builder equipmentSlot(String slotName) {
            this.equipmentSlotName = slotName;
            return this;
        }

        /** Crafting recipe of the item. */
        public Builder recipe(Recipe recipe) {
            this.recipe = recipe;
            return this;
        }

        /** Java callback for a right click, run alongside the YML action list. */
        public Builder onRightClick(BiConsumer<Player, ItemStack> callback) {
            this.onRightClick = callback;
            return this;
        }

        /** Java callback for a left click. */
        public Builder onLeftClick(BiConsumer<Player, ItemStack> callback) {
            this.onLeftClick = callback;
            return this;
        }

        /** Java callback for a shift right click. */
        public Builder onShiftRightClick(BiConsumer<Player, ItemStack> callback) {
            this.onShiftRightClick = callback;
            return this;
        }

        /** Java callback for a shift left click. */
        public Builder onShiftLeftClick(BiConsumer<Player, ItemStack> callback) {
            this.onShiftLeftClick = callback;
            return this;
        }

        /** Java callback for a right click on a block. */
        public Builder onRightClickBlock(BiConsumer<Player, ItemStack> callback) {
            this.onRightClickBlock = callback;
            return this;
        }

        /** Java callback for a right click in the air. */
        public Builder onRightClickAir(BiConsumer<Player, ItemStack> callback) {
            this.onRightClickAir = callback;
            return this;
        }

        /** Java callback for a left click on a block. */
        public Builder onLeftClickBlock(BiConsumer<Player, ItemStack> callback) {
            this.onLeftClickBlock = callback;
            return this;
        }

        /** Java callback for a left click in the air. */
        public Builder onLeftClickAir(BiConsumer<Player, ItemStack> callback) {
            this.onLeftClickAir = callback;
            return this;
        }

        /** Builds the immutable definition. */
        public ItemDef build() {
            return new ItemDef(this);
        }
    }

    /**
     * Immutable recipe declaration of the golden spec. Material names are stored raw and
     * resolved leniently by the recipe loading layer.
     */
    public static final class Recipe {

        private final String type;
        private final List<String> shape;
        private final Map<Character, String> ingredients;
        private final List<String> shapelessIngredients;
        private final @Nullable String input;
        private final double experience;
        private final int cookingTime;

        private Recipe(String type, List<String> shape, Map<Character, String> ingredients,
                List<String> shapelessIngredients, @Nullable String input, double experience,
                int cookingTime) {
            this.type = type;
            this.shape = shape == null ? List.of() : List.copyOf(shape);
            this.ingredients = ingredients == null ? Map.of() : Map.copyOf(ingredients);
            this.shapelessIngredients =
                    shapelessIngredients == null ? List.of() : List.copyOf(shapelessIngredients);
            this.input = input;
            this.experience = experience;
            this.cookingTime = cookingTime;
        }

        /** Shaped recipe from up to three shape rows and a symbol-to-material map. */
        public static Recipe shaped(List<String> shape, Map<Character, String> ingredients) {
            return new Recipe("SHAPED", shape, ingredients, null, null, 0, 0);
        }

        /** Shapeless recipe from a flat material list. */
        public static Recipe shapeless(List<String> ingredients) {
            return new Recipe("SHAPELESS", null, null, ingredients, null, 0, 0);
        }

        /** Cooking recipe: {@code type} is FURNACE, SMOKING, BLASTING or CAMPFIRE. */
        public static Recipe cooking(String type, String input, double experience,
                int cookingTimeTicks) {
            return new Recipe(normalizeType(type), null, null, null, input, experience,
                    cookingTimeTicks);
        }

        /** Stonecutting recipe from a single input material. */
        public static Recipe stonecutting(String input) {
            return new Recipe("STONECUTTING", null, null, null, input, 0, 0);
        }

        /** Parses the {@code recipe:} section; an empty or unknown type yields null. */
        static @Nullable Recipe fromSection(ConfigurationSection sec, Consumer<String> warn) {
            String type = normalizeType(sec.getString("type", ""));
            if (type.isEmpty()) {
                return null;
            }
            switch (type) {
                case "SHAPED" -> {
                    List<String> shape = sec.getStringList("shape");
                    Map<Character, String> ingredients = new LinkedHashMap<>();
                    ConfigurationSection ing = sec.getConfigurationSection("ingredients");
                    if (ing != null) {
                        for (String symbol : ing.getKeys(false)) {
                            if (!symbol.isEmpty()) {
                                ingredients.put(symbol.charAt(0), ing.getString(symbol, ""));
                            }
                        }
                    }
                    if (shape.isEmpty() || ingredients.isEmpty()) {
                        warn.accept("Receta SHAPED sin shape o sin ingredients; se ignora");
                        return null;
                    }
                    return shaped(shape, ingredients);
                }
                case "SHAPELESS" -> {
                    List<String> ingredients = sec.getStringList("ingredients");
                    if (ingredients.isEmpty()) {
                        warn.accept("Receta SHAPELESS sin ingredients; se ignora");
                        return null;
                    }
                    return shapeless(ingredients);
                }
                case "FURNACE", "SMOKING", "BLASTING", "CAMPFIRE" -> {
                    String input = sec.getString("input", "");
                    if (input.isEmpty()) {
                        warn.accept("Receta " + type + " sin input; se ignora");
                        return null;
                    }
                    return cooking(type, input, sec.getDouble("experience", 0.0),
                            sec.getInt("cooking-time", 200));
                }
                case "STONECUTTING" -> {
                    String input = sec.getString("input", "");
                    if (input.isEmpty()) {
                        warn.accept("Receta STONECUTTING sin input; se ignora");
                        return null;
                    }
                    return stonecutting(input);
                }
                default -> {
                    warn.accept("Tipo de receta desconocido '" + type + "'; se ignora");
                    return null;
                }
            }
        }

        private static String normalizeType(@Nullable String raw) {
            return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        }

        /** Recipe type: SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE or STONECUTTING. */
        public String type() {
            return type;
        }

        /** Shape rows of a SHAPED recipe; empty otherwise. */
        public List<String> shape() {
            return shape;
        }

        /** Symbol-to-material map of a SHAPED recipe; empty otherwise. */
        public Map<Character, String> ingredients() {
            return ingredients;
        }

        /** Flat material list of a SHAPELESS recipe; empty otherwise. */
        public List<String> shapelessIngredients() {
            return shapelessIngredients;
        }

        /** Input material of cooking and stonecutting recipes, or null. */
        public @Nullable String input() {
            return input;
        }

        /** Experience granted by cooking recipes. */
        public double experience() {
            return experience;
        }

        /** Cooking time in ticks of cooking recipes. */
        public int cookingTime() {
            return cookingTime;
        }
    }
}
