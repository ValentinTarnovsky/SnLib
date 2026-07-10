package com.sn.lib.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;

/**
 * Reusable GUI item without slots, declared under the {@code templates:} section of a GUI
 * file: the config user customizes the appearance and actions freely while the plugin
 * decides at runtime which slot each template goes to via
 * {@link GuiSession#bind(int, GuiTemplate, Ph...)}.
 *
 * <p>Templates support the exact same fields as regular items except {@code slots:} and
 * typically use plugin-defined local placeholders (for example {@code %index%}) supplied
 * as {@link Ph} pairs at bind time.</p>
 */
public final class GuiTemplate {

    private final GuiItemDef item;

    GuiTemplate(GuiItemDef item) {
        this.item = item;
    }

    /** Template id (its key inside the {@code templates:} section). */
    public String id() {
        return item.id();
    }

    /** Builds the physical stack for {@code viewer} with the given local placeholders. */
    public ItemStack render(@Nullable Player viewer, Ph... phs) {
        return item.render(viewer, phs);
    }

    /** Backing definition (requirements and action lists) used by the click flow. */
    GuiItemDef item() {
        return item;
    }
}
