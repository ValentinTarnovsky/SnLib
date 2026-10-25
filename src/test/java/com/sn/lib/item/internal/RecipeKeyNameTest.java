package com.sn.lib.item.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeLoader#keyName(String)}, the recipe key shared by register and
 * unregister: an item id unregistered under any spelling it was registered with names the
 * same server recipe.
 */
class RecipeKeyNameTest {

    @Test
    void theIdIsFoldedToTheKeyCharset() {
        assertEquals("snlib_recipe_soulblade", RecipeLoader.keyName("soulblade"));
        assertEquals("snlib_recipe_soul_blade", RecipeLoader.keyName("Soul Blade"));
        assertEquals("snlib_recipe_arena_key-1", RecipeLoader.keyName(" arena:key-1 "));
    }
}
