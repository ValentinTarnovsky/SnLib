package com.sn.lib.item;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.event.EquipMethod;
import com.sn.lib.event.SnArmourEquipEvent;
import com.sn.lib.item.internal.DurabilityTracker;
import com.sn.lib.item.internal.EquipmentBackup;
import com.sn.lib.item.internal.HeldEffectsTask;
import com.sn.lib.item.internal.ItemPropertyListener;
import com.sn.lib.item.internal.LockedItemListener;
import com.sn.lib.item.internal.RecipeLoader;
import com.sn.lib.util.InvUtil;
import com.sn.lib.util.TagIo;
import com.sn.lib.yml.SnYml;

/**
 * Per-context registry of item definitions, reached through {@code sn.items()}.
 *
 * <p>Works with ZERO files: definitions can be registered fully programmatically via
 * {@link ItemDef#builder()}, from a YML section via {@link #register(String, SnYml)}, or
 * in bulk from the items file declared in the spec via {@link #loadAll}. Created stacks
 * are tagged with the owner-namespaced PDC key {@code snlib_item_id} (through
 * {@link TagIo}), which is how the shared property listener resolves any stack back to
 * its owning context.</p>
 */
public final class ItemRegistry {

    /** PDC key name carrying the item id; namespaced per owner plugin by {@link TagIo}. */
    public static final String TAG_KEY = "snlib_item_id";

    /** PDC flag key set on created stacks of a locked definition. */
    public static final String TAG_LOCKED = "snlib_locked";

    /** PDC flag key set on created stacks of a no-drop definition. */
    public static final String TAG_NO_DROP = "snlib_no_drop";

    /** PDC flag key set on created stacks of a no-manual-equip definition. */
    public static final String TAG_NO_MANUAL_EQUIP = "snlib_no_manual_equip";

    /** PDC flag key set on created stacks of a keep-on-death definition. */
    public static final String TAG_KEEP_ON_DEATH = "snlib_keep_on_death";

    /** PDC key carrying the obtain mode of restricted definitions. */
    public static final String TAG_OBTAIN_VIA = "snlib_obtain_via";

    /** The six player equipment slots; a fixed list keeps the source enum open. */
    private static final List<EquipmentSlot> PLAYER_SLOTS = List.of(EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final Sn ctx;
    private final JavaPlugin plugin;
    private final EquipmentBackup backup;
    private final RecipeLoader recipes;
    private final HeldEffectsTask heldEffects;
    private final Map<String, ItemDef> defs = new ConcurrentHashMap<>();

    /** Creates the registry for the given context and tracks it for owner resolution. */
    public ItemRegistry(Sn ctx) {
        this.ctx = ctx;
        this.plugin = ctx.plugin();
        this.backup = new EquipmentBackup(ctx);
        this.recipes = new RecipeLoader(plugin, this);
        this.heldEffects = new HeldEffectsTask(ctx, this);
        ItemPropertyListener.track(plugin, this);
    }

    /**
     * Parses and registers the definition found at the top-level section {@code id} of
     * {@code yml}; a missing section logs one WARN and registers nothing. Re-registering
     * an id replaces the previous definition.
     */
    public void register(String id, SnYml yml) {
        ItemDef def = ItemDef.fromYml(yml, id, message -> plugin.getLogger().warning(message));
        if (def != null) {
            register(id, def);
        }
    }

    /**
     * Registers a definition under {@code id}, replacing any previous one. A declared
     * recipe is added to the server under {@code snlib_recipe_<id>} (looked up before
     * registering, so re-registrations never throw) and held-effect lines start the
     * per-context held-effects timer lazily.
     */
    public void register(String id, ItemDef def) {
        if (id == null || id.isBlank() || def == null) {
            plugin.getLogger().warning("register de item ignorado: id o definicion nulos");
            return;
        }
        String key = id.trim();
        defs.put(key, def);
        recipes.register(key, def);
        heldEffects.track(key, def);
    }

    /** Registers every top-level section of {@code itemsFile} as one item definition. */
    public void loadAll(SnYml itemsFile) {
        ConfigurationSection root = itemsFile.getSection("");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            if (root.isConfigurationSection(id)) {
                register(id, itemsFile);
            }
        }
    }

    /** Definition registered under {@code id}, or null. */
    public @Nullable ItemDef def(String id) {
        return id == null ? null : defs.get(id.trim());
    }

    /**
     * Builds the physical stack for {@code id}, tagged with the owner-namespaced
     * {@code snlib_item_id}. Appearance placeholders resolve against {@code viewer} plus
     * the extra locals {@code phs}. Definitions with custom durability come out seeded at
     * full durability with their lore line rendered. An unknown id logs one WARN and
     * returns null.
     */
    public @Nullable ItemStack create(String id, @Nullable Player viewer, Ph... phs) {
        ItemDef def = def(id);
        if (def == null) {
            plugin.getLogger().warning("Item desconocido '" + id + "': no esta registrado");
            return null;
        }
        ItemStack stack = def.buildStack(viewer, phs);
        TagIo.set(stack, plugin, TAG_KEY, id.trim());
        tagLockedFlags(stack, def);
        DurabilityTracker.initialize(plugin, def, stack);
        return stack;
    }

    /**
     * Injects the item registered under {@code id} into the player's equipment slot
     * (the command/API path of {@code obtain-via: COMMAND_ONLY}). The displaced real
     * item is backed up write-through by the equipment backup, whose restore runs on
     * quit and on shutdown. Fires a cancellable {@link SnArmourEquipEvent}
     * ({@link EquipMethod#PICK_DROP}, marked programmatic) before touching the slot and
     * the definition's {@code onApply} hook after.
     *
     * @return true when the item ended up equipped
     */
    public boolean apply(Player player, String id, EquipmentSlot slot) {
        if (player == null || slot == null) {
            return false;
        }
        ItemDef def = def(id);
        if (def == null) {
            plugin.getLogger().warning("apply de item desconocido '" + id
                    + "': no esta registrado");
            return false;
        }
        ItemStack stack = create(id, player);
        if (stack == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack displaced = inventory.getItem(slot);
        LockedItemListener.markProgrammatic(player.getUniqueId(), slot);
        SnArmourEquipEvent equip = new SnArmourEquipEvent(player, EquipMethod.PICK_DROP,
                slot, normalize(displaced), stack);
        if (!equip.call()) {
            return false;
        }
        backup.store(player, slot, displaced);
        inventory.setItem(slot, stack);
        BiConsumer<Player, ItemStack> onApply = def.onApply();
        if (onApply != null) {
            onApply.accept(player, stack);
        }
        return true;
    }

    /**
     * Removes every applied instance of {@code id} from the player's equipment slots,
     * restoring the backed-up real item of each slot (null empties it). Fires a
     * cancellable {@link SnArmourEquipEvent} per slot and the definition's
     * {@code onRemove} hook after each removal.
     *
     * @return true when at least one slot was restored
     */
    public boolean unapply(Player player, String id) {
        if (player == null || id == null) {
            return false;
        }
        ItemDef def = def(id);
        PlayerInventory inventory = player.getInventory();
        boolean removed = false;
        for (EquipmentSlot slot : PLAYER_SLOTS) {
            ItemStack current = inventory.getItem(slot);
            if (!is(current, id)) {
                continue;
            }
            ItemStack real = backup.peek(player.getUniqueId(), slot);
            LockedItemListener.markProgrammatic(player.getUniqueId(), slot);
            SnArmourEquipEvent equip = new SnArmourEquipEvent(player, EquipMethod.PICK_DROP,
                    slot, current, real);
            if (!equip.call()) {
                continue;
            }
            backup.take(player.getUniqueId(), slot);
            inventory.setItem(slot, real);
            removed = true;
            BiConsumer<Player, ItemStack> onRemove = def == null ? null : def.onRemove();
            if (onRemove != null) {
                onRemove.accept(player, current);
            }
        }
        return removed;
    }

    /**
     * Remaining custom durability of the stack; an untagged stack of a durability item
     * counts as full. Returns -1 when the stack was not created by this context or its
     * definition has no custom durability.
     */
    public int durability(ItemStack item) {
        ItemDef def = defOf(item);
        return def == null ? -1 : DurabilityTracker.durability(plugin, def, item);
    }

    /**
     * Subtracts {@code amount} custom durability from the stack (floored at 0), updating
     * its tag and re-rendering the lore line. Break-actions and hand removal only run
     * through the interact flow, which has the using player; a programmatic break is the
     * caller's to handle.
     *
     * @return the remaining durability (0 means broken), or -1 when the stack was not
     *         created by this context or its definition has no custom durability
     */
    public int damage(ItemStack item, int amount) {
        ItemDef def = defOf(item);
        return def == null ? -1 : DurabilityTracker.damage(plugin, def, item, amount);
    }

    /** Definition behind a stack created by this context, or null. */
    private @Nullable ItemDef defOf(ItemStack item) {
        String id = idOf(item);
        return id == null ? null : def(id);
    }

    /** Registered id of the stack when this context created it, or null. */
    public @Nullable String idOf(ItemStack item) {
        return TagIo.get(item, plugin, TAG_KEY);
    }

    /** Whether the stack is an instance of the item registered under {@code id}. */
    public boolean is(ItemStack item, String id) {
        if (id == null) {
            return false;
        }
        String tagged = idOf(item);
        return tagged != null && tagged.equals(id.trim());
    }

    /**
     * Gives {@code amount} units of the item to the player, splitting into max-stack
     * chunks; whatever does not fit is dropped at the player's feet.
     */
    public void give(Player player, String id, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        ItemStack prototype = create(id, player);
        if (prototype == null) {
            return;
        }
        int maxStack = Math.max(1, prototype.getMaxStackSize());
        int remaining = amount;
        while (remaining > 0) {
            int chunk = Math.min(remaining, maxStack);
            ItemStack part = prototype.clone();
            part.setAmount(chunk);
            InvUtil.giveItems(player, part);
            remaining -= chunk;
        }
    }

    /** Writes the locked-mode PDC flags declared by the definition onto the stack. */
    private void tagLockedFlags(ItemStack stack, ItemDef def) {
        if (def.locked()) {
            TagIo.set(stack, plugin, TAG_LOCKED, "true");
        }
        if (def.noDrop()) {
            TagIo.set(stack, plugin, TAG_NO_DROP, "true");
        }
        if (def.noManualEquip()) {
            TagIo.set(stack, plugin, TAG_NO_MANUAL_EQUIP, "true");
        }
        if (def.keepOnDeath()) {
            TagIo.set(stack, plugin, TAG_KEEP_ON_DEATH, "true");
        }
        if (def.obtainVia() != ObtainMode.UNRESTRICTED) {
            TagIo.set(stack, plugin, TAG_OBTAIN_VIA, def.obtainVia().name());
        }
    }

    private static @Nullable ItemStack normalize(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() ? null : item;
    }
}
