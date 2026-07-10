package com.sn.lib.gui;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.action.Requirement;
import com.sn.lib.action.RequirementEngine;
import com.sn.lib.item.SnItem;
import com.sn.lib.util.SlotParser;
import com.sn.lib.yml.SnYml;

/**
 * One item of a GUI definition: the full appearance section of the golden spec plus
 * slots, per-item update interval, view/click requirements and click/deny action lists.
 *
 * <p>Appearance is NOT pre-built: the definition keeps its yml section and re-reads it on
 * every {@link #render}, so name, lore and every other string resolve per viewer through
 * the SnYml pipeline (locals, PAPI, {@code [rgb]}, {@code [center]}, MiniMessage).
 * Requirements are parsed ONCE at load from the raw section (bypassing placeholder
 * resolution, so tokens reach evaluation intact); action lines stay raw for the action
 * engine, which resolves them at run time.</p>
 */
public final class GuiItemDef {

    private final String id;
    private final SnYml yml;
    private final String path;
    private final int[] slots;
    private final int updateInterval;
    private final Requirement viewRequirement;
    private final Requirement clickRequirement;
    private final List<String> clickActions;
    private final List<String> denyActions;

    private GuiItemDef(String id, SnYml yml, String path, int[] slots, int updateInterval,
                       Requirement viewRequirement, Requirement clickRequirement,
                       List<String> clickActions, List<String> denyActions) {
        this.id = id;
        this.yml = yml;
        this.path = path;
        this.slots = slots;
        this.updateInterval = updateInterval;
        this.viewRequirement = viewRequirement;
        this.clickRequirement = clickRequirement;
        this.clickActions = clickActions;
        this.denyActions = denyActions;
    }

    /**
     * Parses the item found at {@code path} inside {@code yml}; warnings go to
     * {@code warn}. Returns null when the section does not exist.
     */
    static @Nullable GuiItemDef parse(SnYml yml, String path, String id, Consumer<String> warn) {
        ConfigurationSection sec = yml.getSection(path);
        if (sec == null) {
            warn.accept("No existe la seccion '" + path + "' en " + yml.file().getName()
                    + "; item ignorado");
            return null;
        }
        int[] slots = SlotParser.parse(sec.get("slots"),
                sec.isSet("slots") ? message -> warn.accept("Item '" + id + "': " + message) : null);
        int updateInterval = Math.max(0, sec.getInt("update-interval", 0));
        Requirement viewReq = RequirementEngine.parse(sec.getStringList("view-requirements"),
                message -> warn.accept("Item '" + id + "': " + message));
        Requirement clickReq = RequirementEngine.parse(sec.getStringList("click-requirements"),
                message -> warn.accept("Item '" + id + "': " + message));
        List<String> clickActions = List.copyOf(sec.getStringList("click-actions"));
        List<String> denyActions = List.copyOf(sec.getStringList("deny-actions"));
        return new GuiItemDef(id, yml, path, slots, updateInterval, viewReq, clickReq,
                clickActions, denyActions);
    }

    /** Item id (its key inside the {@code items:} or {@code templates:} section). */
    public String id() {
        return id;
    }

    /** Slots the item renders into; empty for templates, whose slots come from binds. */
    public int[] slots() {
        return slots.clone();
    }

    /** True when the item declared at least one slot. */
    boolean hasSlots() {
        return slots.length > 0;
    }

    /** Per-item re-render interval in ticks; 0 disables the item timer. */
    public int updateInterval() {
        return updateInterval;
    }

    /** Requirement gating whether the item renders for a viewer; always passes when absent. */
    public Requirement viewRequirement() {
        return viewRequirement;
    }

    /** Requirement gating clicks; failing it runs {@link #denyActions()} instead. */
    public Requirement clickRequirement() {
        return clickRequirement;
    }

    /** Raw action lines run on a click that passes the click requirement. */
    public List<String> clickActions() {
        return clickActions;
    }

    /** Raw action lines run when the click requirement fails. */
    public List<String> denyActions() {
        return denyActions;
    }

    /**
     * Builds the physical stack for {@code viewer}, re-reading every appearance field from
     * the yml section so placeholders resolve per viewer plus the extra locals {@code phs}.
     */
    public ItemStack render(@Nullable Player viewer, Ph... phs) {
        return SnItem.fromConfig(yml, path, viewer, phs).build();
    }
}
