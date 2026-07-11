package com.sn.lib.gui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
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
 *
 * <p>Per-click matrix: besides the generic {@code click-actions} /
 * {@code click-requirements} / {@code deny-actions}, five click keys each read three
 * optional lists:
 * {@code right-click-actions}, {@code right-click-requirements},
 * {@code right-click-deny-actions},
 * {@code left-click-actions}, {@code left-click-requirements},
 * {@code left-click-deny-actions},
 * {@code shift-right-click-actions}, {@code shift-right-click-requirements},
 * {@code shift-right-click-deny-actions},
 * {@code shift-left-click-actions}, {@code shift-left-click-requirements},
 * {@code shift-left-click-deny-actions},
 * {@code middle-click-actions}, {@code middle-click-requirements},
 * {@code middle-click-deny-actions}.
 * A list counts as declared only when it is non-empty. Resolution is field by field and
 * specific-over-generic: the shift entry of the click wins first, then the side entry
 * (RIGHT groups RIGHT/SHIFT_RIGHT; LEFT groups LEFT/SHIFT_LEFT/DOUBLE_CLICK/CREATIVE,
 * consistent with {@link ClickType#isLeftClick()}; MIDDLE stands alone), then the generic
 * field. Each field falls back independently, so an item may declare
 * {@code right-click-actions} without {@code right-click-requirements} and its
 * requirement still resolves from the generic {@code click-requirements}.</p>
 */
public final class GuiItemDef {

    /** Navigation role of the item, detected from its click actions at parse time. */
    enum NavKind {
        NONE,
        PREVIOUS,
        NEXT
    }

    /** Keys of the per-click matrix; shift keys win over side keys on resolution. */
    enum ClickKey {
        RIGHT,
        LEFT,
        SHIFT_RIGHT,
        SHIFT_LEFT,
        MIDDLE
    }

    /** Per-click fields of one matrix entry; null means the field was not declared. */
    private record PerClick(List<String> actions, @Nullable Requirement requirement,
                            @Nullable List<String> denyActions) {
    }

    private final String id;
    private final SnYml yml;
    private final String path;
    private final int[] slots;
    private final int updateInterval;
    private final Requirement viewRequirement;
    private final Requirement clickRequirement;
    private final List<String> clickActions;
    private final List<String> denyActions;
    private final Map<ClickKey, PerClick> perClick;
    private final NavKind navKind;
    private final @Nullable GuiItemDef navDisabled;

    private GuiItemDef(String id, SnYml yml, String path, int[] slots, int updateInterval,
                       Requirement viewRequirement, Requirement clickRequirement,
                       List<String> clickActions, List<String> denyActions,
                       Map<ClickKey, PerClick> perClick,
                       NavKind navKind, @Nullable GuiItemDef navDisabled) {
        this.id = id;
        this.yml = yml;
        this.path = path;
        this.slots = slots;
        this.updateInterval = updateInterval;
        this.viewRequirement = viewRequirement;
        this.clickRequirement = clickRequirement;
        this.clickActions = clickActions;
        this.denyActions = denyActions;
        this.perClick = perClick.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(perClick));
        this.navKind = navKind;
        this.navDisabled = navDisabled;
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
        Map<ClickKey, PerClick> perClick = parsePerClick(sec, id, warn);
        NavKind navKind = detectNav(clickActions, perClick);
        GuiItemDef navDisabled = null;
        if (navKind != NavKind.NONE && sec.getConfigurationSection("nav-disabled") != null) {
            navDisabled = parse(yml, path + ".nav-disabled", id + ".nav-disabled", warn);
        }
        return new GuiItemDef(id, yml, path, slots, updateInterval, viewReq, clickReq,
                clickActions, denyActions, perClick, navKind, navDisabled);
    }

    /**
     * Reads the per-click matrix: one entry per click key that declared at least one of
     * its three lists. A specific requirement is parsed ONCE and only when its list is
     * non-empty; an absent one stays null so resolution can fall back to the generic
     * {@code click-requirements} instead of an always-true placeholder.
     */
    private static Map<ClickKey, PerClick> parsePerClick(ConfigurationSection sec, String id,
                                                         Consumer<String> warn) {
        Map<ClickKey, PerClick> perClick = new EnumMap<>(ClickKey.class);
        for (ClickKey key : ClickKey.values()) {
            String prefix = key.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-click-";
            List<String> actions = List.copyOf(sec.getStringList(prefix + "actions"));
            List<String> requirementLines = sec.getStringList(prefix + "requirements");
            List<String> deny = sec.getStringList(prefix + "deny-actions");
            Requirement requirement = requirementLines.isEmpty() ? null
                    : RequirementEngine.parse(requirementLines,
                            message -> warn.accept("Item '" + id + "': " + message));
            List<String> denyActions = deny.isEmpty() ? null : List.copyOf(deny);
            if (!actions.isEmpty() || requirement != null || denyActions != null) {
                perClick.put(key, new PerClick(actions, requirement, denyActions));
            }
        }
        return perClick;
    }

    /**
     * Scans every action list (the generic one plus the five per-click entries) for the
     * pagination tags that make this a navigation item.
     */
    private static NavKind detectNav(List<String> clickActions, Map<ClickKey, PerClick> perClick) {
        NavKind kind = scanNav(clickActions);
        if (kind != NavKind.NONE) {
            return kind;
        }
        for (PerClick entry : perClick.values()) {
            kind = scanNav(entry.actions());
            if (kind != NavKind.NONE) {
                return kind;
            }
        }
        return NavKind.NONE;
    }

    private static NavKind scanNav(List<String> actions) {
        for (String line : actions) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("[previous-page]")) {
                return NavKind.PREVIOUS;
            }
            if (lower.contains("[next-page]")) {
                return NavKind.NEXT;
            }
        }
        return NavKind.NONE;
    }

    /** Matrix key of an exact shift click, or null for every other click. */
    static @Nullable ClickKey shiftKey(@Nullable ClickType click) {
        if (click == ClickType.SHIFT_RIGHT) {
            return ClickKey.SHIFT_RIGHT;
        }
        if (click == ClickType.SHIFT_LEFT) {
            return ClickKey.SHIFT_LEFT;
        }
        return null;
    }

    /**
     * Matrix key of the click's side: RIGHT groups RIGHT and SHIFT_RIGHT; LEFT groups
     * LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE (consistent with
     * {@link ClickType#isLeftClick()}); MIDDLE stands alone. Keyboard and unknown clicks
     * (NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND, UNKNOWN, window border clicks) have
     * no side and return null.
     */
    static @Nullable ClickKey sideKey(@Nullable ClickType click) {
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            return ClickKey.RIGHT;
        }
        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT
                || click == ClickType.DOUBLE_CLICK || click == ClickType.CREATIVE) {
            return ClickKey.LEFT;
        }
        if (click == ClickType.MIDDLE) {
            return ClickKey.MIDDLE;
        }
        return null;
    }

    /** True only for the four basic mouse clicks: LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT. */
    static boolean basicClick(@Nullable ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT
                || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }

    private @Nullable PerClick entry(@Nullable ClickKey key) {
        return key == null ? null : perClick.get(key);
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
     * Action lines for {@code click}: the declared shift entry wins, then the side entry,
     * then the generic {@link #clickActions()}. A null click resolves to the generic list.
     */
    public List<String> clickActionsFor(@Nullable ClickType click) {
        PerClick shift = entry(shiftKey(click));
        if (shift != null && !shift.actions().isEmpty()) {
            return shift.actions();
        }
        PerClick side = entry(sideKey(click));
        if (side != null && !side.actions().isEmpty()) {
            return side.actions();
        }
        return clickActions;
    }

    /**
     * Requirement for {@code click}: the declared shift entry wins, then the side entry,
     * then the generic {@link #clickRequirement()}. Never null; each field resolves
     * independently, so a specific actions list may pair with the generic requirement.
     */
    public Requirement clickRequirementFor(@Nullable ClickType click) {
        PerClick shift = entry(shiftKey(click));
        if (shift != null && shift.requirement() != null) {
            return shift.requirement();
        }
        PerClick side = entry(sideKey(click));
        if (side != null && side.requirement() != null) {
            return side.requirement();
        }
        return clickRequirement;
    }

    /**
     * Deny action lines for {@code click}: the declared shift entry wins, then the side
     * entry, then the generic {@link #denyActions()}.
     */
    public List<String> denyActionsFor(@Nullable ClickType click) {
        PerClick shift = entry(shiftKey(click));
        if (shift != null && shift.denyActions() != null) {
            return shift.denyActions();
        }
        PerClick side = entry(sideKey(click));
        if (side != null && side.denyActions() != null) {
            return side.denyActions();
        }
        return denyActions;
    }

    /**
     * True when {@code click} resolves its actions from a declared specific list (shift
     * or side entry) instead of the generic fallback; consumed by the strict-clicks gate.
     */
    boolean specificActionsFor(@Nullable ClickType click) {
        PerClick shift = entry(shiftKey(click));
        if (shift != null && !shift.actions().isEmpty()) {
            return true;
        }
        PerClick side = entry(sideKey(click));
        return side != null && !side.actions().isEmpty();
    }

    /** Navigation role of the item; sessions gate disabled arrows through it. */
    NavKind navKind() {
        return navKind;
    }

    /**
     * Appearance override rendered INSTEAD of this navigation item when there is no page
     * to go to (first page for previous, last page for next), or null when not declared.
     * A disabled navigation item never fires any action.
     */
    @Nullable GuiItemDef navDisabled() {
        return navDisabled;
    }

    /**
     * Builds the physical stack for {@code viewer}, re-reading every appearance field from
     * the yml section so placeholders resolve per viewer plus the extra locals {@code phs}.
     */
    public ItemStack render(@Nullable Player viewer, Ph... phs) {
        return SnItem.fromConfig(yml, path, viewer, phs).build();
    }
}
