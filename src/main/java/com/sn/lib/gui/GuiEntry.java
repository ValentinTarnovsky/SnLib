package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;

/**
 * One cell of a {@link GuiSession#bindEach} region while it is being filled: the filler
 * picks the template that paints this entry and adds the entry's local placeholders. The
 * session hands out a fresh instance per entry per render and never lets the filler see a
 * slot index - the placement belongs to the yml.
 *
 * <p>Same accumulator role {@link PhCollector} plays for the {@code bindPaged} mapper, plus
 * the per-entry template choice a paged bind does not need: the cells of one region
 * routinely alternate between state variants of the same button (allowed/denied,
 * selected/unselected), so no single template can own the placement. Both types also carry
 * the optional per-entry {@link #stack(ItemStack)} appearance override (1.21.0).</p>
 */
public final class GuiEntry {

    private final List<Ph> phs = new ArrayList<>();

    private String templateId = "";
    private @Nullable ItemStack stack;

    GuiEntry() {
    }

    /**
     * Template of the menu's {@code templates:} section that paints this entry; the last
     * call wins and the call is chainable. Null, empty or never called leaves the cell to
     * the item declared on it, which is how an entry is deliberately skipped without
     * punching a hole; an id the {@code templates:} section does not declare WARNs once per
     * GUI and region and does the same.
     *
     * <p>The chosen template's own {@code slots:}/{@code key:} are ignored - the region
     * already decided the cell; only its appearance, requirements and actions are used.</p>
     */
    public GuiEntry template(String templateId) {
        this.templateId = templateId == null ? "" : templateId;
        return this;
    }

    /**
     * Adds a local placeholder pair via {@link Ph#of}; null or empty keys are ignored. Same
     * contract as {@link PhCollector#add}. The pairs reach the rendered item, its
     * {@code view-requirements} and the {@link com.sn.lib.action.ActionContext} of its
     * clicks, so the entry's identity belongs here and never in its position: a click
     * carries the id the filler added, which is what makes reordering the cells safe.
     */
    public GuiEntry add(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            phs.add(Ph.of(key, value));
        }
        return this;
    }

    /**
     * Appearance of THIS cell, supplied by the plugin: the stack is rendered INSTEAD of the
     * chosen template's own appearance, with only that template's declared
     * {@code display-name} (replacing) and {@code lore} (appended) painted over it. Last
     * call wins and the call is chainable; a null stack (or never calling this) is exactly
     * the pre-1.21.0 behaviour, the template paints the cell. A stack still needs
     * {@link #template(String)}: the template is what carries the click behaviour, and a
     * cell with no definition behind it would be inert.
     *
     * <p>Same contract as {@link PhCollector#stack(ItemStack)}: the stack is copied before
     * anything is written to it, so the caller's instance is never mutated and never reaches
     * the inventory, and everything else about the entry - {@code view-requirements}, the
     * per-click matrix, the fallthrough to the declared item, the anti-theft marker - is
     * unchanged. This is the surface for cells whose contents the plugin does not author
     * (crate rewards, kit items, shop stock), whose NBT no yml item definition can
     * re-express.</p>
     */
    public GuiEntry stack(@Nullable ItemStack stack) {
        this.stack = stack;
        return this;
    }

    /** Template id the filler chose; empty when it chose none. */
    String templateId() {
        return templateId;
    }

    /** Stack the filler supplied for this cell, or null when it supplied none. */
    @Nullable ItemStack stack() {
        return stack;
    }

    /** Collected pairs in insertion order, exactly like {@link PhCollector#toArray()}. */
    Ph[] toArray() {
        return phs.toArray(new Ph[0]);
    }
}
