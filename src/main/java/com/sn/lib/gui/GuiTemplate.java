package com.sn.lib.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;

/**
 * Reusable GUI item declared under the {@code templates:} section of a GUI file: the
 * config user customizes the appearance and actions freely while the plugin supplies the
 * runtime data at bind time. Placement comes from either side: the template may declare
 * {@code slots:} or {@code key:} (resolved against the menu {@code layout:} exactly like
 * an item, since 1.18.0) and be bound with the no-slot
 * {@link GuiSession#bind(String, Ph...)}, so the server owner repositions it by editing
 * the file; or the plugin decides the slot in Java via
 * {@link GuiSession#bind(int, GuiTemplate, Ph...)}, which ignores the declared cells.
 *
 * <p>A template painted by a runtime REGION ({@link GuiSession#bindEach}, since 1.20.0) is
 * the third case: the cell comes from the region's declaration under {@code regions:} and
 * the template's own {@code slots:}/{@code key:} are ignored there, only its appearance,
 * requirements and actions are used. That is what lets several state variants of one button
 * (allowed/denied, selected/unselected) serve the same region without any of them declaring
 * a placement - and why a template used only by a region should stay keyless.</p>
 *
 * <p>Templates support the exact same fields as regular items ({@code slots:}/{@code key:}
 * optional instead of required) and typically use plugin-defined local placeholders (for
 * example {@code %index%}) supplied as {@link Ph} pairs at bind time.</p>
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

    /**
     * Cells the template declared in the yml through {@code slots:} or {@code key:}
     * (declared slots win over key, like items); empty when it declares neither. These
     * are the target of the no-slot {@link GuiSession#bind(String, Ph...)}.
     */
    public int[] slots() {
        return item.slots();
    }

    /** True when the template declared a placement ({@code slots:} or a valid {@code key:}). */
    public boolean hasSlots() {
        return item.hasSlots();
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
