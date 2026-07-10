package com.sn.lib.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.item.internal.DurabilityTracker;
import com.sn.lib.item.internal.ItemPropertyListener;
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

    private final Sn ctx;
    private final JavaPlugin plugin;
    private final Map<String, ItemDef> defs = new ConcurrentHashMap<>();

    /** Creates the registry for the given context and tracks it for owner resolution. */
    public ItemRegistry(Sn ctx) {
        this.ctx = ctx;
        this.plugin = ctx.plugin();
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

    /** Registers a definition under {@code id}, replacing any previous one. */
    public void register(String id, ItemDef def) {
        if (id == null || id.isBlank() || def == null) {
            plugin.getLogger().warning("register de item ignorado: id o definicion nulos");
            return;
        }
        defs.put(id.trim(), def);
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
        DurabilityTracker.initialize(plugin, def, stack);
        return stack;
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
}
