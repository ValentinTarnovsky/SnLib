package com.sn.lib.item.internal;

import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.item.ItemDef;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Per-context loader of the crafting recipes declared by item definitions: SHAPED,
 * SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE and STONECUTTING.
 *
 * <p>Every recipe registers under {@code NamespacedKey(plugin, "snlib_recipe_" + itemId)}
 * with the consumer plugin as owner, and ALWAYS looks the key up before registering
 * ({@code Bukkit.getRecipe(key) == null} gate) so a second enable never throws.
 * Registered keys are tracked in a multi-tenant registry whose sweep callback removes
 * the recipe from the server: the tenant sweeper cleans an owner's recipes on disable,
 * and {@link #unregisterAll()} / {@link #registerAll} give the reload manager its
 * unregister/re-register cycle. Ingredient materials resolve leniently with WARN.</p>
 */
public final class RecipeLoader {

    /** Recipe keys by owner; the sweep callback removes each recipe from the server. */
    private static final TenantRegistry<NamespacedKey> KEYS =
            new TenantRegistry<>(Bukkit::removeRecipe);

    private final JavaPlugin plugin;
    private final ItemRegistry registry;

    public RecipeLoader(JavaPlugin plugin, ItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * Registers the recipe declared by {@code def} under {@code snlib_recipe_<itemId>};
     * a definition without recipe is a no-op. The result stack is the registry's created
     * item (PDC id included). Invalid declarations WARN and register nothing.
     */
    public void register(String itemId, ItemDef def) {
        ItemDef.Recipe declared = def.recipe();
        if (declared == null) {
            return;
        }
        NamespacedKey key = keyFor(itemId);
        if (key == null) {
            return;
        }
        if (Bukkit.getRecipe(key) != null) {
            KEYS.add(plugin, key);
            return;
        }
        ItemStack result = registry.create(itemId, null);
        if (result == null) {
            return;
        }
        Recipe recipe = build(key, result, declared, itemId);
        if (recipe == null) {
            return;
        }
        try {
            if (Bukkit.addRecipe(recipe)) {
                KEYS.add(plugin, key);
            } else {
                warn("addRecipe rejected the recipe of '" + itemId + "'");
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            warn("Could not register the recipe of '" + itemId + "': " + e.getMessage());
        }
    }

    /** Registers the recipe of every definition; the reload manager's re-register half. */
    public void registerAll(Map<String, ItemDef> defs) {
        defs.forEach(this::register);
    }

    /** Removes every recipe key of this owner from the server. */
    public void unregisterAll() {
        unregisterAll(plugin);
    }

    /** Removes every recipe key of {@code owner} from the server (teardown path). */
    public static void unregisterAll(Plugin owner) {
        KEYS.removeOwner(owner);
    }

    private @Nullable Recipe build(NamespacedKey key, ItemStack result, ItemDef.Recipe recipe,
            String itemId) {
        return switch (recipe.type()) {
            case "SHAPED" -> buildShaped(key, result, recipe, itemId);
            case "SHAPELESS" -> buildShapeless(key, result, recipe, itemId);
            case "FURNACE", "SMOKING", "BLASTING", "CAMPFIRE" ->
                    buildCooking(key, result, recipe, itemId);
            case "STONECUTTING" -> buildStonecutting(key, result, recipe, itemId);
            default -> {
                warn("Unknown recipe type '" + recipe.type() + "' in '" + itemId
                        + "'; ignored");
                yield null;
            }
        };
    }

    private @Nullable Recipe buildShaped(NamespacedKey key, ItemStack result,
            ItemDef.Recipe recipe, String itemId) {
        try {
            ShapedRecipe shaped = new ShapedRecipe(key, result);
            shaped.shape(recipe.shape().toArray(new String[0]));
            for (Map.Entry<Character, String> entry : recipe.ingredients().entrySet()) {
                Material material = resolveMaterial(entry.getValue());
                if (material == null) {
                    warn("Invalid ingredient '" + entry.getValue() + "' (symbol '"
                            + entry.getKey() + "') in the recipe of '" + itemId
                            + "'; recipe ignored");
                    return null;
                }
                shaped.setIngredient(entry.getKey(), material);
            }
            return shaped;
        } catch (IllegalArgumentException invalid) {
            warn("Invalid SHAPED recipe in '" + itemId + "' (" + invalid.getMessage()
                    + "); ignored");
            return null;
        }
    }

    private @Nullable Recipe buildShapeless(NamespacedKey key, ItemStack result,
            ItemDef.Recipe recipe, String itemId) {
        ShapelessRecipe shapeless = new ShapelessRecipe(key, result);
        int added = 0;
        for (String raw : recipe.shapelessIngredients()) {
            Material material = resolveMaterial(raw);
            if (material == null) {
                warn("Invalid ingredient '" + raw + "' in the recipe of '" + itemId
                        + "'; skipped");
                continue;
            }
            shapeless.addIngredient(material);
            added++;
        }
        if (added == 0) {
            warn("SHAPELESS recipe of '" + itemId + "' has no valid ingredients; ignored");
            return null;
        }
        return shapeless;
    }

    private @Nullable Recipe buildCooking(NamespacedKey key, ItemStack result,
            ItemDef.Recipe recipe, String itemId) {
        Material input = resolveMaterial(recipe.input());
        if (input == null) {
            warn("Invalid input '" + recipe.input() + "' in the recipe of '" + itemId
                    + "'; ignored");
            return null;
        }
        float experience = (float) recipe.experience();
        int cookingTime = Math.max(1, recipe.cookingTime());
        return switch (recipe.type()) {
            case "SMOKING" -> new SmokingRecipe(key, result, input, experience, cookingTime);
            case "BLASTING" -> new BlastingRecipe(key, result, input, experience, cookingTime);
            case "CAMPFIRE" -> new CampfireRecipe(key, result, input, experience, cookingTime);
            default -> new FurnaceRecipe(key, result, input, experience, cookingTime);
        };
    }

    private @Nullable Recipe buildStonecutting(NamespacedKey key, ItemStack result,
            ItemDef.Recipe recipe, String itemId) {
        Material input = resolveMaterial(recipe.input());
        if (input == null) {
            warn("Invalid input '" + recipe.input() + "' in the recipe of '" + itemId
                    + "'; ignored");
            return null;
        }
        return new StonecuttingRecipe(key, result, input);
    }

    /** Owner-namespaced recipe key; item ids are normalized to the key charset. */
    private @Nullable NamespacedKey keyFor(String itemId) {
        String cleaned = itemId.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9/._-]", "_");
        try {
            return new NamespacedKey(plugin, "snlib_recipe_" + cleaned);
        } catch (IllegalArgumentException invalid) {
            warn("Item id '" + itemId + "' does not produce a valid recipe key; ignored");
            return null;
        }
    }

    private static @Nullable Material resolveMaterial(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Material match = Material.matchMaterial(raw.trim());
        if (match != null) {
            return match;
        }
        return Material.matchMaterial(
                raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
    }

    private void warn(String message) {
        plugin.getLogger().warning(message);
    }
}
