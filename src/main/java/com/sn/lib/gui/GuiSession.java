package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.action.ActionContext;
import com.sn.lib.action.PageTarget;
import com.sn.lib.action.Requirement;
import com.sn.lib.item.internal.SkinResolver;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.internal.TenantSweeper;
import com.sn.lib.text.SnText;
import com.sn.lib.util.SoundUtil;
import com.sn.lib.util.TagIo;

/**
 * Live GUI of ONE viewer: every viewer gets their own session with their OWN inventory,
 * OWN {@link SnGuiHolder} and OWN page state, all sharing the immutable {@link GuiDef}
 * and its templates. N players inside the same GUI are N independent sessions on
 * possibly different pages; there is no shared per-GUI inventory.
 *
 * <p>Rendering is per viewer: view requirements, placeholders and the title resolve
 * against this session's player. Several declared items may target the same slot (the
 * same layout {@code key:} or overlapping {@code slots:}): candidates are tried in
 * declaration order and the FIRST one whose view requirement passes for this viewer owns
 * the cell, so mutually exclusive variants of one button need no priority field - declare
 * the preferred item first. A slot every candidate hides from renders empty. Update
 * intervals (menu-level and per-item) run through
 * cancelable task handles; the menu tick re-evaluates title and rows and, when they
 * changed, recreates the inventory with the SAME holder and session, preserving page and
 * binds. Every rendered stack is stamped with the owner-namespaced PDC key
 * {@code snlib_gui_item} carrying {@code "<guiId>:<slot>"}.</p>
 *
 * <p>Paged data enters through {@link #bindPaged}: the paged slots render the CURRENT
 * page of THIS viewer from an immutable {@link Pagination} snapshot, and navigation items
 * declared in the YML gate themselves through their optional {@code nav-disabled}
 * override (a disabled arrow renders the override and fires nothing). Clicks and closes
 * are dispatched by the shared click listener into {@link #handleClick} and
 * {@link #handleClose}; a natural close additionally plays the menu's optional
 * {@code close-sound} and schedules its {@code close-actions} (never on page swaps nor
 * on programmatic teardown, see {@link #handleClose}).</p>
 *
 * <p>Runtime REGIONS enter through {@link #bindEach}: the menu declares a named group of
 * cells under {@code regions:} and the plugin lays one entry per cell over it, a filler
 * choosing that entry's template and placeholders on every render. Unlike a paged bind, a
 * region owns its cells one by one: a cell with no entry, one whose filler picked no
 * template and one hidden by its view requirement all fall through to the item declared
 * underneath, on the screen and on the click alike. Precedence over a shared cell is manual
 * bind, then paged bind, then region, then declared item.</p>
 *
 * <p>All three bind surfaces accept a PLUGIN-SUPPLIED appearance since 1.21.0:
 * {@link #bind(int, GuiTemplate, ItemStack, Ph...)}, {@link PhCollector#stack} for a paged
 * entry and {@link GuiEntry#stack} for a region cell. The stack is rendered instead of the
 * template's own appearance while the template keeps supplying the behaviour, with only its
 * {@code display-name} (replacing) and {@code lore} (appended) painted over the stack. That
 * is what lets a menu show stacks the plugin did not author - crate rewards, kit contents,
 * shop stock - whose NBT no yml item definition can re-express. A null stack is exactly the
 * template-rendered behaviour.</p>
 *
 * <p><b>Menus can RECEIVE an item since 1.28.0</b>: a cell the yml declared
 * {@code input: true} - or one holding a bound template that did - reports the stack a
 * viewer aims at it to the handler registered through {@link #onOffer}, as an
 * {@link ItemOffer}, instead of firing the cell's click actions. The menu-level
 * {@code player-inventory: open} additionally leaves the viewer's own inventory usable and
 * turns a shift-click there into an offer. Every event behind an offer is CANCELLED before
 * the handler runs and the offer carries a clone, so SnLib reads the item and never
 * consumes it; what accepting one means, and writing back the remainder of a partial
 * deposit, belong to the consumer. A menu declaring neither key behaves exactly as it did
 * before 1.28.0.</p>
 *
 * <p><b>View requirements gate interaction, not only rendering</b>: a click resolves what
 * the slot actually shows to this viewer, under the same precedence, the same
 * declaration-order fallthrough on shared slots and the same requirement test the render
 * uses, so an item hidden from a viewer can never be clicked by them -
 * {@code view-requirements} need no duplicate in {@code click-requirements}.</p>
 *
 * <p>As a {@link PageTarget}, page operations are gated by the menu's opt-in
 * {@code pagination} flag: with pagination false, {@link #nextPage()},
 * {@link #previousPage()}, {@link #setPage(int)} and {@link #refreshPage()} are no-ops
 * with a debug note. Main-thread only, like the whole GUI module.</p>
 */
public final class GuiSession implements PageTarget {

    /** Shared empty local-placeholder array of the definitions that declare none. */
    private static final Ph[] NO_LOCALS = new Ph[0];

    private final Sn ctx;
    private final Gui gui;
    private final GuiDef def;
    private final Player viewer;
    private final SnGuiHolder holder;
    private final Map<Integer, List<GuiItemDef>> baseSlots = new ConcurrentHashMap<>();
    private final Map<Integer, Binding> binds = new ConcurrentHashMap<>();
    private final Map<Integer, Ph[]> pagedPhs = new ConcurrentHashMap<>();
    private final Map<Integer, Binding> regionCells = new ConcurrentHashMap<>();
    private final Set<Integer> inputBinds = ConcurrentHashMap.newKeySet();
    private final List<TaskHandle> tasks = new CopyOnWriteArrayList<>();

    private volatile Inventory inventory;
    private volatile Component lastTitle;
    private volatile int page;
    private volatile boolean transitioningPage;
    private volatile boolean closed;
    private volatile @Nullable PagedBind<?> pagedBind;
    private volatile Set<Integer> pagedSlots = Set.of();
    private volatile Map<String, RegionBind<?>> regionBinds = Map.of();
    private volatile int manualTotalPages;
    private volatile Ph[] titlePhs = NO_LOCALS;
    private volatile @Nullable Consumer<ItemOffer> offerHandler;
    private boolean typeWarned;
    private boolean navUnknownNoted;

    GuiSession(Sn ctx, Gui gui, Player viewer, int initialPage) {
        this.ctx = ctx;
        this.gui = gui;
        this.def = gui.def();
        this.viewer = viewer;
        this.page = def.pagination() ? Math.max(1, initialPage) : 1;
        this.holder = new SnGuiHolder(ctx.plugin(), def.id(), this);
        for (GuiItemDef item : def.items()) {
            for (int slot : item.slots()) {
                baseSlots.computeIfAbsent(slot, unused -> new ArrayList<>(1)).add(item);
            }
        }
    }

    /** First open: creates the inventory, renders, tracks the holder and starts timers. */
    void open() {
        Component title = renderTitle();
        Inventory fresh = createInventory(title);
        this.lastTitle = title;
        this.inventory = fresh;
        holder.inventory(fresh);
        renderContents();
        TenantSweeper.trackInventory(holder);
        viewer.openInventory(fresh);
        playOpenSound();
        startTimers();
    }

    /** Re-entry through {@code Gui.open} on an existing session: sets page and re-shows. */
    void reopen(int targetPage) {
        if (closed) {
            return;
        }
        this.page = def.pagination() ? Math.max(1, targetPage) : 1;
        boolean wasViewing = isViewing();
        refreshMenu();
        if (!isViewing()) {
            viewer.openInventory(inventory);
        }
        if (!wasViewing) {
            playOpenSound();
        }
    }

    /** Player this session belongs to. */
    public Player viewer() {
        return viewer;
    }

    /** UUID of the session's viewer. */
    public UUID viewerId() {
        return viewer.getUniqueId();
    }

    /** GUI id of the backing definition. */
    public String guiId() {
        return def.id();
    }

    /** Current page of THIS viewer (1-based); always 1 while pagination is off. */
    public int page() {
        return page;
    }

    /** Holder shared by every inventory this session recreates. */
    SnGuiHolder holder() {
        return holder;
    }

    /**
     * True while the session is swapping inventories (page change or recreation); the
     * close handling of the click listener skips removal during a transition.
     */
    public boolean transitioningPage() {
        return transitioningPage;
    }

    /** True once the session was closed and unregistered. */
    public boolean closed() {
        return closed;
    }

    /**
     * Definition rendered at {@code slot} for this viewer: an API bind takes precedence,
     * then a paged entry, then the region entry painted there (1.20.0), then the declared
     * item of that slot. Null for an empty slot, which includes a definition hidden from
     * this viewer by its {@code view-requirements}, a paged slot the current page left empty
     * and a region cell no entry filled - the last one falls through to the declared item
     * rather than reading as empty, since a region owns its cells one by one.
     */
    public @Nullable GuiItemDef itemAt(int slot) {
        Rendered rendered = renderedAt(slot);
        return rendered == null ? null : rendered.item();
    }

    /**
     * Binds a template into the cells IT declares in the yml ({@code slots:} or
     * {@code key:} against the menu {@code layout:}), so the server owner repositions the
     * element by editing the file instead of the placement living hardcoded in Java. A
     * key covering N cells renders the same bind (same placeholders) into every cell.
     * Same lifetime and precedence as {@link #bind(int, GuiTemplate, Ph...)}, which stays
     * the explicit variant for plugin-computed placement (per-entry lists, etc.) and
     * ignores the declared cells.
     *
     * <p>An unknown template id, or a template that declares neither {@code slots:} nor a
     * valid {@code key:}, WARNs once per GUI and is ignored.</p>
     *
     * <p>This is the ONE-element form: every cell of the key shows the same thing. A group
     * of cells that needs one DISTINCT entry each (a matrix, a selector, a non-paged list)
     * is a region - see {@link #bindEach}.</p>
     */
    public void bind(String templateId, Ph... phs) {
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            ctx.guis().warnOnce("bind-template:" + def.id() + ":" + templateId,
                    "bind on gui '" + def.id() + "' ignored: template '" + templateId
                            + "' does not exist");
            return;
        }
        int[] slots = template.slots();
        if (slots.length == 0) {
            ctx.guis().warnOnce("bind-template-slots:" + def.id() + ":" + templateId,
                    "bind on gui '" + def.id() + "' ignored: template '" + templateId
                            + "' declares neither 'slots' nor a valid 'key'; declare one"
                            + " in the yml or use bind(slot, template)");
            return;
        }
        for (int slot : slots) {
            bind(slot, template, phs);
        }
    }

    /**
     * Binds a template to a slot of THIS session with the given local placeholders and
     * renders it immediately. The bind survives page refreshes and inventory recreations
     * until overwritten; it takes precedence over a declared item on the same slot.
     */
    public void bind(int slot, GuiTemplate template, Ph... phs) {
        bind(slot, template, (ItemStack) null, phs);
    }

    /**
     * Same bind with the APPEARANCE supplied by the plugin: {@code stack} is rendered into
     * the slot instead of the template's own appearance, and the template keeps supplying
     * the BEHAVIOUR - {@code view-requirements}, the per-click matrix, the click and deny
     * actions. This is the surface for stacks the plugin did not author (a crate reward,
     * kit contents, shop stock, a lootbox preview), whose enchantments, custom model data,
     * head texture and custom name no yml item definition can re-express.
     *
     * <p>Only two things of the template are painted over the stack: its
     * {@code display-name}, when it declares a non-empty one, REPLACES the stack's name, and
     * its {@code lore} lines, when it declares any, are APPENDED after the stack's own lore,
     * both resolved through the normal pipeline (viewer PAPI, the locals {@code phs}, colour,
     * rgb). A template that declares neither leaves the stack visually untouched; nothing
     * else of it is applied, because the stack is the authority on how the cell looks.</p>
     *
     * <p>The stack is copied on the way in and again on every render, so the caller's
     * instance is never mutated and never reaches the inventory. Everything else is
     * identical to {@link #bind(int, GuiTemplate, Ph...)}: same precedence, same lifetime
     * across page changes, refreshes and inventory recreations, same anti-theft marker on
     * the rendered stack. A null {@code stack} IS that method - the template renders the
     * slot - so this overload is strictly additive.</p>
     */
    public void bind(int slot, GuiTemplate template, @Nullable ItemStack stack, Ph... phs) {
        if (template == null || slot < 0) {
            return;
        }
        Binding binding = new Binding(template, phs == null ? NO_LOCALS : phs.clone(),
                stack == null ? null : stack.clone());
        binds.put(slot, binding);
        // A bind OWNS the input state of the cell it takes: binding a template that declares
        // input: true makes the slot an input slot of THIS session, and binding a plain one
        // over it takes the cell back out of the offer path.
        if (template.item().input()) {
            inputBinds.add(slot);
        } else {
            inputBinds.remove(slot);
        }
        Inventory current = inventory;
        if (current != null && slot < current.getSize()) {
            renderBinding(current, slot, binding);
        }
    }

    /**
     * Fills a runtime REGION of this session: an immutable snapshot of {@code data} is laid
     * over the cells the menu declares for {@code regionId} under {@code regions:}, one
     * entry per cell in the order the file declares them, and {@code filler} runs once per
     * entry on EVERY render to pick the template that paints it
     * ({@link GuiEntry#template(String)}) and fill its local placeholders. Which cells, how
     * many, in which order and whether the region exists at all live entirely in the yml,
     * so the server owner repositions, resizes, splits, reorders or removes the region by
     * editing the file and the plugin never names a slot. An entry's identity travels in
     * the placeholders the filler adds, never in its index, so reordering the cells
     * reorders the picture and never the data.
     *
     * <p>Cardinality belongs to the owner: more entries than cells renders the first cells
     * worth and drops the tail, more cells than entries leaves the spare cells to the items
     * declared on them, and both are silent with a debug note - a region sized down is
     * configuration, not a mistake. Ownership is per CELL, not per region: a cell with no
     * entry, an entry whose filler picked no template and an entry hidden by its own
     * {@code view-requirements} all fall through to the declared item underneath, on the
     * screen and on the click alike. Re-binding replaces the whole region, so a shorter list
     * releases its tail on the same repaint - a region leaves no stale entry to clear by
     * hand.</p>
     *
     * <p>Unlike {@link #bind(String, Ph...)}, which renders the SAME bind into every cell of
     * a template's declared key, every cell here gets its own entry; unlike
     * {@link #bindPaged}, a region needs no {@code pagination: true}, never touches the page
     * and shows the same entries on every page. A manual {@link #bind(int, GuiTemplate,
     * Ph...)} and the paged bind both outrank a region on a cell they share. The bind
     * survives page changes, refreshes and inventory recreations until rebound, and the
     * filler re-runs on each of them, so entry values stay live under
     * {@code update-interval:} instead of freezing at bind time.</p>
     *
     * <p>A region id the menu does not declare WARNs once per GUI and is ignored; a declared
     * region whose letter the owner took out of the layout binds nothing, silently, which is
     * how a region is turned off. A null {@code data} clears the region; a null filler
     * throws. Main-thread only, like the whole GUI module: the filler runs inside the
     * render, so it must stay cheap and must never block - call it from a scheduler hop, not
     * from a database continuation.</p>
     */
    public <T> void bindEach(String regionId, List<T> data, BiConsumer<T, GuiEntry> filler) {
        Objects.requireNonNull(filler, "filler");
        int[] declared = def.region(regionId);
        if (declared == null) {
            ctx.guis().warnOnce("bind-each:" + def.id() + ":" + regionId,
                    "bindEach on gui '" + def.id() + "' ignored: the menu declares no region"
                            + " '" + regionId + "' under 'regions:'; to REMOVE a region keep"
                            + " the declaration and take its letter out of the layout");
            return;
        }
        List<T> snapshot = data == null ? List.of() : List.copyOf(data);
        noteRegionFit(regionId, declared.length, snapshot.size());
        Map<String, RegionBind<?>> updated = new HashMap<>(regionBinds);
        updated.put(regionId, new RegionBind<>(declared.clone(), snapshot, filler));
        this.regionBinds = Map.copyOf(updated);
        if (!closed && inventory != null) {
            renderContents();
        }
    }

    /**
     * ONE debug note per bind (never per render) when the owner's cell count and the
     * plugin's entry count disagree. Never a WARN: how many cells a region has is
     * owner-authored configuration the plugin cannot resolve, so under the 1.19.1 rule it
     * is a note. A plugin that must not truncate silently reads
     * {@link GuiDef#regionSlots(String)} and says so in its own words.
     */
    private void noteRegionFit(String regionId, int cells, int entries) {
        if (cells == entries) {
            return;
        }
        String tail = cells < entries
                ? (entries - cells) + " not shown"
                : (cells - entries) + " cell(s) left to the declared items";
        ctx.debug().log(() -> "GUI '" + def.id() + "': region '" + regionId + "' has " + cells
                + " cell(s) for " + entries + " entr(ies); " + tail);
    }

    /**
     * Binds a paged data set to THIS session: an immutable snapshot of {@code data} is
     * paged by {@code slots.length} entries and the CURRENT page of this viewer renders
     * into {@code slots} using the template, one entry per slot in order. The mapper
     * fills the local placeholders of each entry; leftover slots of a short page stay
     * empty. The bind survives page changes and inventory recreations until rebound; the
     * page is clamped to the snapshot's total pages, which also drives the
     * {@code nav-disabled} state of the YML navigation items.
     *
     * <p>With {@code pagination: false} (the menu default) the call is ignored with ONE
     * warning per GUI; an unknown template or empty slots also WARN once and ignore.</p>
     */
    public <T> void bindPaged(String templateId, List<T> data, int[] slots,
                              BiConsumer<T, PhCollector> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (!def.pagination()) {
            ctx.guis().warnOnce("bind-paged:" + def.id(), "bindPaged on gui '" + def.id()
                    + "' ignored: pagination false (opt-in per menu)");
            return;
        }
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            ctx.guis().warnOnce("bind-paged-template:" + def.id() + ":" + templateId,
                    "bindPaged on gui '" + def.id() + "' ignored: template '" + templateId
                            + "' does not exist");
            return;
        }
        if (slots == null || slots.length == 0) {
            ctx.guis().warnOnce("bind-paged-slots:" + def.id(),
                    "bindPaged on gui '" + def.id() + "' ignored: no target slots");
            return;
        }
        int[] target = slots.clone();
        Set<Integer> slotSet = new HashSet<>();
        for (int slot : target) {
            slotSet.add(slot);
        }
        this.pagedBind = new PagedBind<>(template, Pagination.of(data, target.length),
                target, mapper);
        this.pagedSlots = Set.copyOf(slotSet);
        if (!closed && inventory != null) {
            renderContents();
        }
    }

    /**
     * No-slots variant of {@link #bindPaged(String, List, int[], BiConsumer)}: the
     * target slots are the layout cells of the {@code paged-key} declared by the menu.
     * When the menu declares no paged-key the call WARNs once and is ignored. Same
     * rules as the int[] overload (pagination opt-in, existing template).
     */
    public <T> void bindPaged(String templateId, List<T> data, BiConsumer<T, PhCollector> mapper) {
        int[] target = def.pagedSlots();
        if (target.length == 0) {
            ctx.guis().warnOnce("bind-paged-key:" + def.id(), "bindPaged on gui '" + def.id()
                    + "' ignored: the menu declares no paged-key in layout");
            return;
        }
        bindPaged(templateId, data, target, mapper);
    }

    /**
     * Click dispatch invoked by the shared click listener with a raw top-inventory slot:
     * resolves what the slot actually renders for this viewer through
     * {@link #renderedAt(int)} (manual bind, paged entry, declared item, each behind its
     * view requirement), skips disabled navigation items and delegates to
     * {@link #runClick}, which resolves the per-click matrix of the definition (actions,
     * requirement and deny list per {@link ClickType}) and applies the menu's opt-in
     * strict-clicks gate.
     *
     * <p>A slot that renders nothing for this viewer fires nothing at all - not even the
     * deny actions: an item hidden by its {@code view-requirements} is unclickable BY
     * THAT SAME expression, so hiding it never needs a duplicate entry in
     * {@code click-requirements}.</p>
     */
    public void handleClick(int slot, ClickType click) {
        if (closed) {
            return;
        }
        Rendered rendered = renderedAt(slot);
        if (rendered == null) {
            clearGhost(slot);
            return;
        }
        if (rendered.declared() && navDisabledNow(rendered.item())) {
            return;
        }
        runClick(rendered.item(), rendered.locals(), click);
    }

    /**
     * Registers what THIS session does with an item its viewer offers it (1.28.0): the
     * handler runs on the main thread, inside the inventory event, for every
     * {@link ItemOffer} the menu produces - a cursor click on an input cell, a drag over
     * one, or a shift-click from a player inventory the menu declared
     * {@code player-inventory: open}.
     *
     * <p>Last write wins and null clears; the handler is dropped when the session closes.
     * With none registered, offers are silently dropped with a debug note, which is a safe
     * no-op: the event was cancelled before the dispatch, so the stack is already back
     * where it came from. SnLib itself never moves an offered stack - see {@link ItemOffer}
     * for the read-not-consume guarantee and for how to write the remainder of a partial
     * deposit back.</p>
     *
     * <p>Main-thread only, like the whole GUI module: the handler runs inside the event, so
     * it must stay cheap and must never block - hop to a scheduler for anything else.</p>
     */
    public void onOffer(@Nullable Consumer<ItemOffer> handler) {
        this.offerHandler = handler;
    }

    /**
     * Offer dispatch invoked by the shared click listener, the parallel of
     * {@link #handleClick}: hands {@code offer} to the handler registered through
     * {@link #onOffer}, or drops it with a debug note when there is none. The event that
     * produced the offer is ALREADY cancelled by the time this runs - that is what returns
     * the stack to the viewer - and nothing here writes it anywhere. No-op on a closed
     * session. Main-thread only.
     */
    public void handleOffer(ItemOffer offer) {
        if (closed || offer == null) {
            return;
        }
        Consumer<ItemOffer> handler = offerHandler;
        if (handler == null) {
            ctx.debug().log(() -> "GUI '" + def.id() + "': " + offer.kind() + " offer of "
                    + offer.stack().getType() + " dropped, no offer handler registered"
                    + " (session.onOffer)");
            return;
        }
        handler.accept(offer);
    }

    /**
     * Whether {@code slot} RECEIVES an item for this session (1.28.0): a cell is an input
     * slot when any definition that can occupy it declares {@code input: true} - the item
     * declared on it in the yml, a template bound to it through
     * {@link #bind(int, GuiTemplate, Ph...)}, or the template a region entry painted there.
     * The yml declaration is therefore never revoked by a bind: a plugin painting the
     * current contents of an input cell does not have to re-declare that the cell accepts
     * an item.
     *
     * <p>Structural on purpose - no view requirement is evaluated, no placeholder resolved:
     * this runs inside the click listener for every click over a menu, so it is three
     * lookups over collections that are empty in a menu with no input cell. What the cell
     * SHOWS is still resolved the usual way for everything else it does.</p>
     */
    public boolean isInputSlot(int slot) {
        if (slot < 0) {
            return false;
        }
        if (def.inputSlot(slot) || inputBinds.contains(slot)) {
            return true;
        }
        Binding cell = regionCells.get(slot);
        return cell != null && cell.template().item().input();
    }

    /**
     * What this menu lets the viewer do with their OWN inventory while it is open
     * ({@code player-inventory:}, 1.28.0), read by the shared click listener; the
     * definition's value, so every session of the menu answers the same.
     */
    public PlayerInventoryPolicy playerInventory() {
        return def.playerInventory();
    }

    /**
     * Close handling invoked by the shared click listener when the viewer's client closed
     * the inventory: same teardown as {@link #close()} without force-closing the screen,
     * plus the menu's optional {@code close-sound} (inline) and {@code close-actions}
     * (scheduled one tick later).
     *
     * <p>Guaranteed by construction: close-sound and close-actions run on the NATURAL
     * close (client ESC) and on the {@code [close]} action (which fires the same
     * InventoryCloseEvent), exactly once per close. They do NOT run on page transitions
     * or inventory recreations (the {@code transitioningPage()} guard in the click
     * listener skips this method) and do NOT run on programmatic teardown ({@link #close()}
     * from the tenant sweep, {@code GuiManager.reload()/closeAll()} or the quit cleanup
     * marks the session closed BEFORE force-closing, so the subsequent close event finds
     * {@code teardown()} false here). Running actions during shutdown is excluded by
     * design. Edge: on a disconnect the server fires InventoryCloseEvent before
     * PlayerQuitEvent; the double {@code isOnline()} guard (here and inside the next-tick
     * task) covers the normal case, but consumers should keep close-actions idempotent.
     * Page actions inside close-actions are useless: the session is already closed.</p>
     */
    public void handleClose() {
        if (!teardown()) {
            return;
        }
        if (!viewer.isOnline()) {
            return;
        }
        playCloseSound();
        runCloseActions();
    }

    @Override
    public void nextPage() {
        if (paginationBlocked("next-page")) {
            return;
        }
        int total = knownTotalPages();
        if (total > 0 && page >= total) {
            return;
        }
        page++;
        refreshPage();
    }

    @Override
    public void previousPage() {
        if (paginationBlocked("previous-page")) {
            return;
        }
        if (page > 1) {
            page--;
            refreshPage();
        }
    }

    @Override
    public void setPage(int targetPage) {
        if (paginationBlocked("set-page")) {
            return;
        }
        int target = Math.max(1, targetPage);
        int total = knownTotalPages();
        if (total > 0 && target > total) {
            target = total;
        }
        page = target;
        refreshPage();
    }

    @Override
    public void refreshPage() {
        if (paginationBlocked("refresh-page")) {
            return;
        }
        if (!closed && inventory != null) {
            renderContents();
        }
    }

    /** Full re-render; recreates the inventory when the resolved title changed. */
    @Override
    public void refreshMenu() {
        if (closed || inventory == null) {
            return;
        }
        Component title = renderTitle();
        if (!title.equals(lastTitle)) {
            recreate(title);
        } else {
            renderContents();
        }
    }

    /**
     * Local placeholders resolved into the {@code title:} of THIS session, so a menu whose
     * subject is not its viewer can name that subject - another player's inventory, the clan
     * or crate being inspected. The declared title stays shared and immutable; only the
     * resolution is per session.
     *
     * <p>Setting them re-renders immediately, recreating the inventory when the resolved
     * title actually changed (a title change cannot be painted into an open window). Prefer
     * {@link Gui#open(Player, Ph...)}, which seeds them before the first frame; this setter
     * is for a title that changes while the menu is already open, and it costs a reopen.
     * Passing none clears them. Main-thread only.</p>
     */
    public void titlePlaceholders(Ph... phs) {
        titlePhs(phs);
        refreshMenu();
    }

    /** Seeds the locals without rendering; how {@link Gui#open} gets the first frame right. */
    void titlePhs(Ph... phs) {
        this.titlePhs = phs == null ? NO_LOCALS : phs.clone();
    }

    @Override
    public boolean paginationEnabled() {
        return def.pagination();
    }

    /**
     * Declares the total pages of a manually paged GUI (paged through
     * {@link #refreshPage()} or custom actions without {@link #bindPaged}): enables the
     * {@link #nextPage()} cap and the {@code nav-disabled} state of the next navigation
     * item. Values {@code <= 0} reset the total to "unknown" (0). A live paged bind takes
     * precedence over this value. Requires {@code pagination: true}; with pagination
     * false this is a no-op with a debug note. Main-thread only.
     */
    public void setTotalPages(int total) {
        if (paginationBlocked("set-total-pages")) {
            return;
        }
        int normalized = Math.max(0, total);
        if (normalized == manualTotalPages) {
            return;
        }
        manualTotalPages = normalized;
        if (closed || inventory == null) {
            return;
        }
        if (normalized > 0 && page > normalized) {
            page = normalized;
        }
        renderContents();
    }

    /**
     * Closes the session: cancels its timers, unregisters it from the per-owner
     * registries, untracks the holder and force-closes the viewer's inventory if this
     * session is still on screen. Idempotent.
     */
    public void close() {
        if (!teardown()) {
            return;
        }
        Inventory current = inventory;
        if (current != null && current.getViewers().contains(viewer)) {
            viewer.closeInventory();
        }
    }

    /** Cancels timers and unregisters the session everywhere; false when already closed. */
    private boolean teardown() {
        if (closed) {
            return false;
        }
        closed = true;
        for (TaskHandle task : tasks) {
            task.cancel();
        }
        tasks.clear();
        // The offer handler is a consumer lambda that may capture the whole plugin state of
        // whatever this menu was editing; a closed session must not keep it alive.
        offerHandler = null;
        inputBinds.clear();
        gui.removeSession(viewer.getUniqueId(), this);
        GuiManager.SESSIONS.remove(ctx.plugin(), this);
        TenantSweeper.untrackInventory(holder);
        return true;
    }

    /**
     * Runs click or deny actions of the definition under this session's context, single
     * funnel for declared items, manual binds and paged entries. Actions, requirement
     * and deny list resolve per {@link ClickType} through the per-click matrix
     * (specific-over-generic, field by field). With {@code strict-clicks: true} a click
     * outside the four basic mouse clicks is discarded BEFORE the requirement test (no
     * click nor deny actions; the listener already cancelled the event) unless a declared
     * specific actions list covers it: {@code middle-click-actions} enables MIDDLE and a
     * declared {@code left-click-actions} enables DOUBLE_CLICK and CREATIVE (a vanilla
     * double click is two lefts, deliberate). NUMBER_KEY, DROP, CONTROL_DROP,
     * SWAP_OFFHAND and UNKNOWN have no possible specific list and stay always discarded
     * in strict mode. With strict false (the default) behaviour is identical to v1.0.0.
     */
    private void runClick(GuiItemDef item, Ph[] phs, ClickType click) {
        if (def.strictClicks() && !GuiItemDef.basicClick(click) && !item.specificActionsFor(click)) {
            ctx.debug().log(() -> "GUI '" + def.id() + "': click " + click
                    + " discarded by strict-clicks (no specific list)");
            return;
        }
        ActionContext context = new ActionContext(viewer, ctx, this, click, phs);
        List<String> actions = item.clickRequirementFor(click).test(viewer, resolver(phs))
                ? item.clickActionsFor(click)
                : item.denyActionsFor(click);
        ctx.actions().run(viewer, actions, context);
    }

    /**
     * What {@code slot} renders for THIS viewer, resolved with the precedence the render
     * phases use: a manual bind first, then the paged bind - which OWNS its slots even
     * when the current page left them empty, so a short page never falls back to the
     * declared item underneath - then the region entry the last render painted there, and
     * finally the declared candidates of the slot in declaration order. Null when the slot
     * shows nothing to this viewer, the definition's own local placeholders otherwise.
     *
     * <p>A region owns a cell only while it actually fills it: a cell with no entry, one
     * whose filler picked no template and one hidden by its view requirement are absent
     * from {@code regionCells} and fall through to the declared item, which is the
     * click-side twin of the render-side fallthrough.</p>
     */
    private @Nullable Rendered renderedAt(int slot) {
        Binding binding = binds.get(slot);
        if (binding != null) {
            return shown(binding.template().item(), binding.phs(), slot, false);
        }
        if (pagedSlots.contains(slot)) {
            PagedBind<?> bind = pagedBind;
            Ph[] locals = pagedPhs.get(slot);
            return bind == null || locals == null ? null
                    : shown(bind.template().item(), locals, slot, false);
        }
        Binding cell = regionCells.get(slot);
        if (cell != null) {
            return shown(cell.template().item(), cell.phs(), slot, false);
        }
        return declaredAt(slot);
    }

    /**
     * Declared candidate the slot currently shows to this viewer: the first item of the
     * slot, in declaration order, whose view requirement passes - the exact rule the
     * render uses, so the click and the screen can never disagree on shared slots. The
     * requirement of a disabled navigation item is tested on its {@code nav-disabled}
     * override, which is what the viewer is actually looking at; the returned definition
     * is always the declared item, whose actions the disabled-navigation gate of
     * {@link #handleClick} still skips.
     */
    private @Nullable Rendered declaredAt(int slot) {
        List<GuiItemDef> candidates = baseSlots.get(slot);
        if (candidates == null) {
            return null;
        }
        for (GuiItemDef candidate : candidates) {
            if (effectiveNow(candidate).viewRequirement().test(viewer, resolver(NO_LOCALS))) {
                return new Rendered(candidate, NO_LOCALS, true);
            }
            ctx.debug().log(() -> "GUI '" + def.id() + "': slot " + slot + " item '"
                    + candidate.id() + "' hidden by its view-requirements for "
                    + viewer.getName());
        }
        return null;
    }

    /**
     * Pairs the definition with its locals while its view requirement passes for this
     * viewer, null once it does not. The requirement is re-evaluated on every call, so a
     * state change that hid the item takes effect immediately instead of waiting for the
     * next render tick.
     */
    private @Nullable Rendered shown(GuiItemDef item, Ph[] locals, int slot, boolean declared) {
        if (item.viewRequirement().test(viewer, resolver(locals))) {
            return new Rendered(item, locals, declared);
        }
        ctx.debug().log(() -> "GUI '" + def.id() + "': slot " + slot + " item '" + item.id()
                + "' hidden by its view-requirements for " + viewer.getName());
        return null;
    }

    /**
     * Clears the stack an older render left on a slot that shows nothing to this viewer
     * any more - its definition is hidden now, or its paged entry is gone - so clicking a
     * ghost item makes the screen converge with the requirement state instead of looking
     * unresponsive. Slots no render phase owns are never touched.
     *
     * <p>Dropping a region cell hands the slot back to the items declared on it, so the
     * declared candidates are re-resolved immediately: without that repaint the cell would
     * sit visually empty while the click already resolved to the declared item underneath,
     * which is the one state where the screen and the click could disagree. The re-render is
     * a no-op for a bind or paged slot (those keep owning the cell through their own guards)
     * and for a slot every declared candidate hides from.</p>
     */
    private void clearGhost(int slot) {
        Inventory current = inventory;
        if (current == null || slot >= current.getSize() || current.getItem(slot) == null) {
            return;
        }
        if (!binds.containsKey(slot) && !pagedSlots.contains(slot)
                && !regionCells.containsKey(slot) && !baseSlots.containsKey(slot)) {
            return;
        }
        pagedPhs.remove(slot);
        regionCells.remove(slot);
        current.setItem(slot, null);
        if (baseSlots.containsKey(slot)) {
            renderDeclared(current, new int[] {slot});
        }
    }

    /**
     * Opt-in gate of every page operation: with pagination false the operation is a
     * no-op recorded through the context debug service.
     */
    private boolean paginationBlocked(String operation) {
        if (def.pagination()) {
            return false;
        }
        ctx.debug().log(() -> "GUI '" + def.id() + "': " + operation
                + " ignored, pagination false (opt-in per menu)");
        return true;
    }

    /**
     * Total pages of the live paged bind, falling back to the manual total declared via
     * {@link #setTotalPages(int)}; 0 when both are unknown.
     */
    private int knownTotalPages() {
        PagedBind<?> bind = pagedBind;
        return bind != null ? bind.pagination().totalPages() : manualTotalPages;
    }

    /**
     * Whether the navigation item is currently disabled for this viewer: previous on the
     * first page, next on the last KNOWN page (a paged bind or a declared
     * {@link #setTotalPages(int)} total). A disabled navigation item renders its
     * {@code nav-disabled} override and fires nothing.
     */
    private boolean navDisabledNow(GuiItemDef item) {
        if (!def.pagination() || item.navKind() == GuiItemDef.NavKind.NONE) {
            return false;
        }
        if (item.navKind() == GuiItemDef.NavKind.PREVIOUS) {
            return page <= 1;
        }
        int total = knownTotalPages();
        return total > 0 && page >= total;
    }

    /** True while this session's inventory is the one on the viewer's screen. */
    private boolean isViewing() {
        Inventory current = inventory;
        return current != null && current.getViewers().contains(viewer);
    }

    /**
     * Recreates the inventory with the SAME holder and session, preserving page and
     * binds, and re-opens it on the viewer; instanceof identification survives.
     */
    private void recreate(Component title) {
        transitioningPage = true;
        try {
            Inventory fresh = createInventory(title);
            this.lastTitle = title;
            this.inventory = fresh;
            holder.inventory(fresh);
            renderContents();
            viewer.openInventory(fresh);
        } finally {
            transitioningPage = false;
        }
    }

    private Inventory createInventory(Component title) {
        if (def.inventoryType() != null) {
            try {
                return Bukkit.createInventory(holder, def.inventoryType(), title);
            } catch (Throwable t) {
                if (!typeWarned) {
                    typeWarned = true;
                    ctx.plugin().getLogger().warning("[gui " + def.id() + "] inventory-type "
                            + def.inventoryType() + " could not be created (" + t + "); using CHEST");
                }
            }
        }
        return Bukkit.createInventory(holder, def.rows() * 9, title);
    }

    /**
     * Title resolved for THIS viewer through the session's locals, PAPI and the full text
     * pipeline. The locals run FIRST, so a placeholder may expand into a PAPI token; with
     * none set {@link SnText#applyLocals} returns the raw title untouched.
     */
    private Component renderTitle() {
        return SnText.color(ctx.papi().apply(viewer,
                SnText.applyLocals(def.title(), titlePhs)));
    }

    private void renderContents() {
        Inventory current = inventory;
        if (current == null) {
            return;
        }
        current.clear();
        // Cleared BEFORE renderDeclared so the declared items repaint into the cells a
        // shrunk region just released; the regions then reclaim only what they still fill.
        regionCells.clear();
        renderDeclared(current, null);
        renderPaged(current);
        renderRegions(current);
        for (Map.Entry<Integer, Binding> entry : binds.entrySet()) {
            if (entry.getKey() < current.getSize()) {
                renderBinding(current, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Renders a declared item by re-resolving the slots it participates in through the
     * same shared-slot fallthrough the full render uses, so a per-item timer or a landed
     * skin can never let a hidden candidate overwrite what another candidate owns.
     */
    private void renderItem(Inventory target, GuiItemDef item) {
        renderDeclared(target, item.slots());
    }

    /**
     * Renders the declared items into the given slots ({@code restrict}; null covers
     * every declared slot), resolving slots shared by several items by declaration-order
     * fallthrough: the first candidate whose view requirement passes for this viewer owns
     * the slot, and a slot every candidate hides from is cleared. Requirements and
     * prototypes resolve ONCE per item and pass, never once per slot; slots taken by a
     * manual bind or by the paged bind are left to their own render phases.
     */
    private void renderDeclared(Inventory target, int @Nullable [] restrict) {
        boolean[] scope = null;
        if (restrict != null) {
            scope = new boolean[target.getSize()];
            for (int slot : restrict) {
                if (slot >= 0 && slot < scope.length) {
                    scope[slot] = true;
                }
            }
        }
        boolean[] owned = new boolean[target.getSize()];
        for (GuiItemDef item : def.items()) {
            renderCandidate(target, item, scope, owned);
        }
        for (int slot = 0; slot < owned.length; slot++) {
            if (owned[slot] || (scope != null && !scope[slot]) || !baseSlots.containsKey(slot)
                    || binds.containsKey(slot) || pagedSlots.contains(slot)
                    || regionCells.containsKey(slot)) {
                continue;
            }
            target.setItem(slot, null);
        }
    }

    /**
     * Renders one declared item into the slots of this pass it can still claim: inside
     * the scope, not owned by an earlier candidate, not taken by a bind or the paged
     * bind. The {@code nav-disabled} swap, the view requirement and the prototype are
     * evaluated lazily on the first claimable slot and only once; a hidden item claims
     * nothing, leaving its slots to the candidates declared after it.
     */
    private void renderCandidate(Inventory target, GuiItemDef item, boolean @Nullable [] scope,
                                 boolean[] owned) {
        ItemStack prototype = null;
        boolean evaluated = false;
        for (int slot : item.slots()) {
            if (slot >= target.getSize() || owned[slot] || (scope != null && !scope[slot])
                    || binds.containsKey(slot) || pagedSlots.contains(slot)
                    || regionCells.containsKey(slot)) {
                continue;
            }
            if (!evaluated) {
                evaluated = true;
                noteNavUnknown(item);
                GuiItemDef effective = effectiveNow(item);
                if (passes(effective.viewRequirement())) {
                    prototype = effective.render(viewer, skinHook(() -> reRenderItem(item)));
                }
            }
            if (prototype == null) {
                return;
            }
            target.setItem(slot, stamp(prototype.clone(), slot));
            owned[slot] = true;
        }
    }

    /**
     * Effective definition the item presents right now: its {@code nav-disabled} override
     * while its navigation direction has no page to go to, the item itself otherwise.
     */
    private GuiItemDef effectiveNow(GuiItemDef item) {
        return navDisabledNow(item) && item.navDisabled() != null ? item.navDisabled() : item;
    }

    /** One-time debug note: a next arrow can never self-disable while the total is unknown. */
    private void noteNavUnknown(GuiItemDef item) {
        if (!navUnknownNoted && def.pagination() && item.navKind() == GuiItemDef.NavKind.NEXT
                && knownTotalPages() == 0) {
            navUnknownNoted = true;
            ctx.debug().log(() -> "GUI '" + def.id() + "': nav next with an unknown total"
                    + " of pages; next is never disabled (use bindPaged or setTotalPages)");
        }
    }

    /** Re-renders a declared item into its slots after an async skin fetch landed. */
    private void reRenderItem(GuiItemDef item) {
        Inventory current = inventory;
        if (closed || current == null) {
            return;
        }
        renderItem(current, item);
    }

    /** Renders the viewer's current page of the paged bind into its slots. */
    private void renderPaged(Inventory target) {
        PagedBind<?> bind = pagedBind;
        if (bind == null) {
            return;
        }
        renderPaged(target, bind);
    }

    private <T> void renderPaged(Inventory target, PagedBind<T> bind) {
        int total = bind.pagination().totalPages();
        if (page > total) {
            page = total;
        }
        List<T> slice = bind.pagination().page(page);
        pagedPhs.clear();
        for (int index = 0; index < bind.slots().length; index++) {
            renderPagedSlot(target, bind, slice, index);
        }
    }

    /**
     * Renders one entry of the paged bind (position {@code index} of the current page's
     * slice) into its slot, wiring the skin hook so an unresolved head re-renders that slot
     * when the fetch lands. Slots taken by a manual bind are left alone; a short page or a
     * failed view requirement clears the slot and its captured placeholders.
     *
     * <p>The mapper may hand over the entry's appearance as a ready-made stack
     * ({@link PhCollector#stack}); it is then rendered under the template's overlay and the
     * skin hook is skipped, since a supplied stack has no {@code skull-owner} to resolve.
     * The stack is read back per render, exactly like the placeholders, so it stays live
     * under {@code update-interval:}.</p>
     */
    private <T> void renderPagedSlot(Inventory target, PagedBind<T> bind, List<T> slice, int index) {
        int[] slots = bind.slots();
        if (index < 0 || index >= slots.length) {
            return;
        }
        int slot = slots[index];
        if (slot < 0 || slot >= target.getSize() || binds.containsKey(slot)) {
            return;
        }
        GuiItemDef item = bind.template().item();
        ItemStack stack = null;
        Ph[] resolved = null;
        if (index < slice.size()) {
            PhCollector collector = new PhCollector();
            bind.mapper().accept(slice.get(index), collector);
            Ph[] phs = collector.toArray();
            if (item.viewRequirement().test(viewer, resolver(phs))) {
                ItemStack supplied = collector.stack();
                if (supplied != null) {
                    stack = stamp(item.renderOver(supplied, viewer, phs), slot);
                } else {
                    int atPage = page;
                    stack = stamp(item.render(viewer,
                            skinHook(() -> reRenderPagedSlot(atPage, index)), phs), slot);
                }
                resolved = phs;
            }
        }
        if (resolved != null) {
            pagedPhs.put(slot, resolved);
        } else {
            pagedPhs.remove(slot);
        }
        target.setItem(slot, stack);
    }

    /** Re-renders a single paged slot after an async skin fetch landed, if still current. */
    private void reRenderPagedSlot(int atPage, int index) {
        Inventory current = inventory;
        PagedBind<?> bind = pagedBind;
        if (closed || page != atPage || current == null || bind == null) {
            return;
        }
        renderPagedSlotAt(current, bind, index);
    }

    private <T> void renderPagedSlotAt(Inventory target, PagedBind<T> bind, int index) {
        renderPagedSlot(target, bind, bind.pagination().page(page), index);
    }

    /**
     * Renders every live region, walking the menu's DECLARED region order rather than the
     * order the plugin bound them, so two regions the owner (mis)declared over the same
     * cells resolve exactly the way the file reads and the parse WARN predicted.
     */
    private void renderRegions(Inventory target) {
        Map<String, RegionBind<?>> live = regionBinds;
        if (live.isEmpty()) {
            return;
        }
        for (String regionId : def.regionIds()) {
            RegionBind<?> bind = live.get(regionId);
            if (bind != null) {
                renderRegion(target, regionId, bind);
            }
        }
    }

    /** Renders the entries of one region that fit its cells; the tail of either side is left alone. */
    private <T> void renderRegion(Inventory target, String regionId, RegionBind<T> bind) {
        int count = Math.min(bind.cells().length, bind.data().size());
        for (int index = 0; index < count; index++) {
            renderRegionCell(target, regionId, bind, index);
        }
    }

    /**
     * Renders entry {@code index} of a region into its cell, running the filler to resolve
     * the template and the entry's local placeholders. WRITES ONLY when it has something to
     * show: a slot outside the inventory, one owned by a manual bind or the paged bind, a
     * filler that picked no template, an unknown template id and a failed view requirement
     * all return WITHOUT touching the cell, so the declared paint underneath survives and
     * {@link #renderedAt(int)} resolves the click to that same item.
     */
    private <T> void renderRegionCell(Inventory target, String regionId, RegionBind<T> bind,
                                      int index) {
        int[] cells = bind.cells();
        if (index < 0 || index >= cells.length || index >= bind.data().size()) {
            return;
        }
        int slot = cells[index];
        if (slot < 0 || slot >= target.getSize() || binds.containsKey(slot)
                || pagedSlots.contains(slot)) {
            return;
        }
        GuiEntry entry = new GuiEntry();
        bind.filler().accept(bind.data().get(index), entry);
        String templateId = entry.templateId();
        if (templateId.isEmpty()) {
            // The supported way to skip ONE entry without punching a hole: the cell keeps
            // whatever the declared items painted there.
            return;
        }
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            ctx.guis().warnOnce(
                    "bind-each-template:" + def.id() + ":" + regionId + ":" + templateId,
                    "bindEach on gui '" + def.id() + "' region '" + regionId + "': template '"
                            + templateId + "' does not exist; entry not rendered");
            return;
        }
        Ph[] phs = entry.toArray();
        GuiItemDef item = template.item();
        if (!item.viewRequirement().test(viewer, resolver(phs))) {
            ctx.debug().log(() -> "GUI '" + def.id() + "': region '" + regionId + "' entry "
                    + index + " hidden by its view-requirements for " + viewer.getName());
            return;
        }
        ItemStack supplied = entry.stack();
        ItemStack painted = supplied != null
                ? item.renderOver(supplied, viewer, phs)
                : item.render(viewer, skinHook(() -> reRenderRegionCell(regionId, bind, index)),
                        phs);
        target.setItem(slot, stamp(painted, slot));
        // The cell's Binding exists for the CLICK side (definition + locals), so it keeps a
        // null stack: a supplied one is per render, resolved from the filler each time and
        // consumed immediately, and retaining the caller's instance here would buy nothing.
        regionCells.put(slot, new Binding(template, phs, null));
    }

    /**
     * Re-renders one region cell after an async skin fetch landed. The guard is the bind
     * IDENTITY, never the index: a rebind between the request and the landing replaces the
     * whole region, and repainting index {@code i} of a stale bind would paint an entry that
     * no longer exists.
     */
    private void reRenderRegionCell(String regionId, RegionBind<?> bind, int index) {
        Inventory current = inventory;
        if (closed || current == null || regionBinds.get(regionId) != bind) {
            return;
        }
        renderRegionCell(current, regionId, bind, index);
    }

    /**
     * Renders one manual bind: the template builds the stack, unless the bind carries a
     * plugin-supplied one, in which case that stack is rendered under the template's
     * appearance overlay ({@link GuiItemDef#renderOver}). The skin hook belongs to the
     * template path only - a supplied stack has no {@code skull-owner} left to resolve.
     */
    private void renderBinding(Inventory target, int slot, Binding binding) {
        GuiItemDef item = binding.template().item();
        ItemStack stack = null;
        if (item.viewRequirement().test(viewer, resolver(binding.phs()))) {
            ItemStack supplied = binding.stack();
            stack = supplied != null
                    ? stamp(item.renderOver(supplied, viewer, binding.phs()), slot)
                    : stamp(item.render(viewer, skinHook(() -> reRenderBinding(slot, binding)),
                            binding.phs()), slot);
        }
        target.setItem(slot, stack);
    }

    /** Re-renders a manually bound slot after an async skin fetch landed, if not re-bound. */
    private void reRenderBinding(int slot, Binding binding) {
        Inventory current = inventory;
        if (closed || current == null || slot >= current.getSize() || binds.get(slot) != binding) {
            return;
        }
        renderBinding(current, slot, binding);
    }

    /**
     * A skin-refresh hook for the render currently in progress: on an unresolved head it
     * asks {@link SkinResolver} to fetch the texture off-thread and, when it lands on the
     * main thread, runs {@code reRender} (which re-checks close/page/rebind guards).
     */
    private Consumer<String> skinHook(Runnable reRender) {
        return owner -> SkinResolver.request(ctx, owner, reRender);
    }

    /** Stamps the anti-theft PDC marker {@code snlib_gui_item} = {@code "<guiId>:<slot>"}. */
    private ItemStack stamp(ItemStack stack, int slot) {
        return TagIo.set(stack, ctx.plugin(), GuiManager.ITEM_TAG, def.id() + ":" + slot);
    }

    private boolean passes(Requirement requirement) {
        return requirement.test(viewer, resolver());
    }

    private Function<String, String> resolver() {
        return token -> ctx.papi().apply(viewer, token);
    }

    private Function<String, String> resolver(Ph[] phs) {
        return token -> ctx.papi().apply(viewer, SnText.applyLocals(token, phs));
    }

    private void playOpenSound() {
        if (!def.openSound().isEmpty()) {
            SoundUtil.play(viewer, def.openSound());
        }
    }

    /** Plays the close sound inline; one sound during InventoryCloseEvent is safe. */
    private void playCloseSound() {
        if (!def.closeSound().isEmpty()) {
            SoundUtil.play(viewer, def.closeSound());
        }
    }

    /**
     * Schedules the menu's close-actions for the NEXT tick, never inline: running
     * {@code [open]}-like actions inside the InventoryCloseEvent itself reopens
     * inventories mid-close and glitches the client; the one-tick hop avoids it. The
     * task re-checks {@code isOnline()} and runs with a null click type (click guards
     * inside close-actions are skipped with a debug note, existing ActionEngine
     * behaviour). Scheduling against a disabled owner is absorbed with a debug note.
     */
    private void runCloseActions() {
        if (def.closeActions().isEmpty()) {
            return;
        }
        try {
            ctx.scheduler().sync(() -> {
                if (!viewer.isOnline()) {
                    return;
                }
                ctx.actions().run(viewer, def.closeActions(),
                        new ActionContext(viewer, ctx, this, null, NO_LOCALS));
            });
        } catch (IllegalPluginAccessException e) {
            ctx.debug().log(() -> "close-actions of '" + def.id()
                    + "' discarded: owner disabled");
        }
    }

    private void startTimers() {
        int menuInterval = def.updateInterval();
        if (menuInterval > 0) {
            tasks.add(ctx.scheduler().timer(menuInterval, menuInterval, this::menuTick));
        }
        for (GuiItemDef item : def.items()) {
            int interval = item.updateInterval();
            if (interval > 0) {
                tasks.add(ctx.scheduler().timer(interval, interval, () -> itemTick(item)));
            }
        }
    }

    private void menuTick() {
        if (closed) {
            return;
        }
        if (!isViewing()) {
            close();
            return;
        }
        refreshMenu();
    }

    private void itemTick(GuiItemDef item) {
        if (closed) {
            return;
        }
        if (!isViewing()) {
            close();
            return;
        }
        renderItem(inventory, item);
    }

    /**
     * Template bound to a slot with its local placeholders captured at bind time, plus the
     * optional plugin-supplied appearance (1.21.0): a non-null {@code stack} is rendered
     * under the template's overlay instead of the template's own appearance, and is already
     * a defensive copy of what the caller passed. Region cells reuse this record for the
     * click side only and always carry a null stack.
     */
    private record Binding(GuiTemplate template, Ph[] phs, @Nullable ItemStack stack) {
    }

    /**
     * Definition a slot currently shows to the viewer, with the locals it renders under.
     * {@code declared} marks the ones that come from the menu's {@code items:} section:
     * only those self-disable as navigation items through their {@code nav-disabled}
     * override, exactly as before this resolution existed.
     */
    private record Rendered(GuiItemDef item, Ph[] locals, boolean declared) {
    }

    /** Live paged bind: template, immutable pagination snapshot, target slots and mapper. */
    private record PagedBind<T>(GuiTemplate template, Pagination<T> pagination, int[] slots,
                                BiConsumer<T, PhCollector> mapper) {
    }

    /**
     * Live region bind: the cells the yml declared, an immutable snapshot of the data and
     * the filler that resolves the template and placeholders of each entry on every render.
     */
    private record RegionBind<T>(int[] cells, List<T> data, BiConsumer<T, GuiEntry> filler) {
    }
}
