package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import com.sn.lib.Ph;
import com.sn.lib.text.SnText;

/**
 * Appearance overlay painted over a PLUGIN-SUPPLIED stack: the stack the plugin hands to a
 * bind is authoritative about how the cell LOOKS, and the template it is bound to keeps
 * owning how the cell BEHAVES. Only two appearance fields cross that line, both of them
 * text the server owner must be able to write:
 *
 * <ul>
 *   <li>{@code display-name}, when the template declares a non-empty one, REPLACES the
 *       stack's name;</li>
 *   <li>{@code lore} lines, when the template declares any, are APPENDED after the lore the
 *       stack already carries.</li>
 * </ul>
 *
 * <p>A template that declares neither is a STRICT pass-through: the clone comes back with
 * nothing added, nothing cleared and nothing normalized, because {@link #apply} never even
 * fetches the meta in that case. A consumer that hands over a fully-formed stack whose lore
 * it built itself gets exactly that stack back. Nothing else of the template is applied -
 * material, amount, enchantments, model data and every other appearance field stay the
 * stack's, because a stack the plugin did not author (a crate reward, a kit item, shop
 * stock) carries NBT that no yml item definition can re-express.
 * Both strings resolve through the normal pipeline: PAPI per viewer while the yml is read,
 * then the local placeholders, then colour, rgb and the rest of {@link SnText}, and each
 * line renders non-italic unless it asks for italics - exactly what
 * {@link com.sn.lib.item.SnItem} does for a template-rendered stack.</p>
 *
 * <p>Split into pure rules ({@link #name}, {@link #lore}) plus the one Bukkit step
 * ({@link #apply}) so the overlay contract is unit-testable without a server; see
 * {@code StackOverlayTest}. Main-thread only, like the whole GUI module.</p>
 */
final class StackOverlay {

    private StackOverlay() {
    }

    /**
     * Display name the template contributes, or null when it declares none - null meaning
     * "leave the supplied stack's own name alone", never "clear it". Emptiness is judged
     * AFTER the local placeholders resolve, so a template whose name is a single placeholder
     * the entry left empty overrides nothing, matching the template-rendered path.
     */
    static @Nullable Component name(@Nullable String declared, Ph... phs) {
        if (declared == null) {
            return null;
        }
        String resolved = SnText.applyLocals(declared, phs);
        return resolved.isEmpty() ? null : render(resolved);
    }

    /**
     * The lore the cell ends up with: {@code existing} (the supplied stack's own lore, null
     * when it has none) followed by the template's declared lines. Null when the template
     * declares no lore, which is the signal to leave the stack's lore untouched - an empty
     * list would clear it instead.
     */
    static @Nullable List<Component> lore(@Nullable List<Component> existing,
                                          @Nullable List<String> declared, Ph... phs) {
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        List<Component> merged =
                new ArrayList<>((existing == null ? 0 : existing.size()) + declared.size());
        if (existing != null) {
            merged.addAll(existing);
        }
        for (String line : declared) {
            append(merged, line, phs);
        }
        return merged;
    }

    /**
     * Paints the overlay over a CLONE of {@code supplied} and returns the clone: the caller's
     * instance is never mutated and never reaches the inventory. A stack with no meta (AIR)
     * is returned as the plain clone.
     *
     * <p><b>Strict pass-through</b>: a template that declares NEITHER field never reads the
     * meta and never writes it, so the result is the plain clone - equal to the input field
     * for field, with nothing added, nothing cleared and nothing normalized. That is the
     * contract a consumer relies on when it hands over a fully-formed stack whose lore it
     * built itself and wants the menu to add exactly nothing. The meta is fetched only once
     * something is actually going to be written to it.</p>
     *
     * <p>Scope note: this is the contract of the OVERLAY. The stack the session finally puts
     * in the inventory additionally carries the {@code snlib_gui_item} anti-theft marker,
     * which every rendered GUI stack has always carried and which is one PDC key written by
     * {@code GuiSession.stamp} after this method returns.</p>
     */
    static ItemStack apply(ItemStack supplied, @Nullable String declaredName,
                           @Nullable List<String> declaredLore, Ph... phs) {
        ItemStack stack = supplied.clone();
        Component name = name(declaredName, phs);
        boolean appendsLore = declaredLore != null && !declaredLore.isEmpty();
        if (name == null && !appendsLore) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.displayName(name);
        }
        if (appendsLore) {
            meta.lore(lore(meta.lore(), declaredLore, phs));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Appends one declared lore line, splitting it on {@code \n} into one line per segment
     * the way {@link com.sn.lib.item.SnItem#lore(List)} does, so a multi-line value flowing
     * through a single placeholder behaves identically on both render paths.
     */
    private static void append(List<Component> target, @Nullable String line, Ph[] phs) {
        String value = line == null ? "" : SnText.applyLocals(line, phs);
        if (value.indexOf('\n') < 0) {
            target.add(render(value));
            return;
        }
        for (String segment : value.split("\n")) {
            target.add(render(segment));
        }
    }

    /** Same rendering {@code SnItem} applies to a name or lore line: pipeline, non-italic. */
    private static Component render(String line) {
        return SnText.color(line)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
