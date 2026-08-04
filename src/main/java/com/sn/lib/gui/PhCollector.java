package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;

/**
 * Accumulator of local placeholder pairs handed to the {@code bindPaged} mapper: the
 * mapper fills one collector per paged entry and the session renders the template with
 * the collected pairs.
 *
 * <p>Since 1.21.0 the mapper may also hand over the entry's APPEARANCE as a ready-made
 * stack through {@link #stack(ItemStack)}, for the paged data a plugin does not author -
 * crate rewards, kit contents, shop stock - whose NBT no yml item definition can
 * re-express. The template keeps supplying the behaviour either way.</p>
 */
public final class PhCollector {

    private final List<Ph> phs = new ArrayList<>();

    private @Nullable ItemStack stack;

    /** Adds a pair via {@link Ph#of}; null or empty keys are ignored. */
    public PhCollector add(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            phs.add(Ph.of(key, value));
        }
        return this;
    }

    /**
     * Appearance of THIS paged entry, supplied by the plugin: the stack is rendered INSTEAD
     * of the template's own appearance, with only the template's declared
     * {@code display-name} (replacing) and {@code lore} (appended) painted over it. Last
     * call wins and the call is chainable; a null stack (or never calling this) is exactly
     * the pre-1.21.0 behaviour, the template renders the cell.
     *
     * <p>The stack is copied before anything is written to it, so the caller's instance is
     * never mutated and the instance handed here never reaches the inventory. Everything
     * else about the entry is unchanged: {@code view-requirements}, the per-click matrix,
     * the anti-theft marker and the bind's lifetime across page changes all behave exactly
     * as they do for a template-rendered entry.</p>
     */
    public PhCollector stack(@Nullable ItemStack stack) {
        this.stack = stack;
        return this;
    }

    /** Collected pairs in insertion order. */
    public Ph[] toArray() {
        return phs.toArray(new Ph[0]);
    }

    /** Stack the mapper supplied for this entry, or null when it supplied none. */
    @Nullable ItemStack stack() {
        return stack;
    }
}
