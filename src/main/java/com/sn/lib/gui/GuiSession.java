package com.sn.lib.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
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
 * against this session's player. Update intervals (menu-level and per-item) run through
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
 * <p>As a {@link PageTarget}, page operations are gated by the menu's opt-in
 * {@code pagination} flag: with pagination false, {@link #nextPage()},
 * {@link #previousPage()}, {@link #setPage(int)} and {@link #refreshPage()} are no-ops
 * with a debug note. Main-thread only, like the whole GUI module.</p>
 */
public final class GuiSession implements PageTarget {

    private final Sn ctx;
    private final Gui gui;
    private final GuiDef def;
    private final Player viewer;
    private final SnGuiHolder holder;
    private final Map<Integer, GuiItemDef> baseSlots = new ConcurrentHashMap<>();
    private final Map<Integer, Binding> binds = new ConcurrentHashMap<>();
    private final Map<Integer, Ph[]> pagedPhs = new ConcurrentHashMap<>();
    private final List<TaskHandle> tasks = new CopyOnWriteArrayList<>();

    private volatile Inventory inventory;
    private volatile Component lastTitle;
    private volatile int page;
    private volatile boolean transitioningPage;
    private volatile boolean closed;
    private volatile @Nullable PagedBind<?> pagedBind;
    private volatile Set<Integer> pagedSlots = Set.of();
    private volatile int manualTotalPages;
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
                baseSlots.put(slot, item);
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
     * then a paged entry, then the declared item of that slot. Null for an empty slot.
     */
    public @Nullable GuiItemDef itemAt(int slot) {
        Binding binding = binds.get(slot);
        if (binding != null) {
            return binding.template().item();
        }
        if (pagedPhs.containsKey(slot)) {
            PagedBind<?> bind = pagedBind;
            if (bind != null) {
                return bind.template().item();
            }
        }
        return baseSlots.get(slot);
    }

    /**
     * Binds a template to a slot of THIS session with the given local placeholders and
     * renders it immediately. The bind survives page refreshes and inventory recreations
     * until overwritten; it takes precedence over a declared item on the same slot.
     */
    public void bind(int slot, GuiTemplate template, Ph... phs) {
        if (template == null || slot < 0) {
            return;
        }
        Binding binding = new Binding(template, phs == null ? new Ph[0] : phs.clone());
        binds.put(slot, binding);
        Inventory current = inventory;
        if (current != null && slot < current.getSize()) {
            renderBinding(current, slot, binding);
        }
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
            ctx.guis().warnOnce("bind-paged:" + def.id(), "bindPaged en gui '" + def.id()
                    + "' ignorado: pagination false (opt-in por menu)");
            return;
        }
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            ctx.guis().warnOnce("bind-paged-template:" + def.id() + ":" + templateId,
                    "bindPaged en gui '" + def.id() + "' ignorado: template '" + templateId
                            + "' no existe");
            return;
        }
        if (slots == null || slots.length == 0) {
            ctx.guis().warnOnce("bind-paged-slots:" + def.id(),
                    "bindPaged en gui '" + def.id() + "' ignorado: sin slots destino");
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
            ctx.guis().warnOnce("bind-paged-key:" + def.id(), "bindPaged en gui '" + def.id()
                    + "' ignorado: el menu no declara paged-key en layout");
            return;
        }
        bindPaged(templateId, data, target, mapper);
    }

    /**
     * Click dispatch invoked by the shared click listener with a raw top-inventory slot:
     * resolves the effective definition (manual bind, paged entry, declared item), skips
     * disabled navigation items and delegates to {@link #runClick}, which resolves the
     * per-click matrix of the definition (actions, requirement and deny list per
     * {@link ClickType}) and applies the menu's opt-in strict-clicks gate.
     */
    public void handleClick(int slot, ClickType click) {
        if (closed) {
            return;
        }
        Binding binding = binds.get(slot);
        if (binding != null) {
            runClick(binding.template().item(), binding.phs(), click);
            return;
        }
        Ph[] pagedLocals = pagedPhs.get(slot);
        if (pagedLocals != null) {
            PagedBind<?> bind = pagedBind;
            if (bind != null) {
                runClick(bind.template().item(), pagedLocals, click);
            }
            return;
        }
        GuiItemDef item = baseSlots.get(slot);
        if (item == null || navDisabledNow(item)) {
            return;
        }
        runClick(item, new Ph[0], click);
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
                    + " descartado por strict-clicks (sin lista especifica)");
            return;
        }
        ActionContext context = new ActionContext(viewer, ctx, this, click, phs);
        List<String> actions = item.clickRequirementFor(click).test(viewer, resolver(phs))
                ? item.clickActionsFor(click)
                : item.denyActionsFor(click);
        ctx.actions().run(viewer, actions, context);
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
                + " ignorado, pagination false (opt-in por menu)");
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
                            + def.inventoryType() + " no se pudo crear (" + t + "); usando CHEST");
                }
            }
        }
        return Bukkit.createInventory(holder, def.rows() * 9, title);
    }

    /** Title resolved for THIS viewer through PAPI and the full text pipeline. */
    private Component renderTitle() {
        return SnText.color(ctx.papi().apply(viewer, def.title()));
    }

    private void renderContents() {
        Inventory current = inventory;
        if (current == null) {
            return;
        }
        current.clear();
        for (GuiItemDef item : def.items()) {
            renderItem(current, item);
        }
        renderPaged(current);
        for (Map.Entry<Integer, Binding> entry : binds.entrySet()) {
            if (entry.getKey() < current.getSize()) {
                renderBinding(current, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Renders a declared item into its slots, swapping in the {@code nav-disabled}
     * override while its navigation direction has no page to go to; slots taken by a
     * manual bind or by the paged bind are left to their own render phases.
     */
    private void renderItem(Inventory target, GuiItemDef item) {
        if (!navUnknownNoted && def.pagination() && item.navKind() == GuiItemDef.NavKind.NEXT
                && knownTotalPages() == 0) {
            navUnknownNoted = true;
            ctx.debug().log(() -> "GUI '" + def.id() + "': nav next con total de paginas"
                    + " desconocido; next nunca se deshabilita (usa bindPaged o setTotalPages)");
        }
        GuiItemDef effective = item;
        if (navDisabledNow(item) && item.navDisabled() != null) {
            effective = item.navDisabled();
        }
        ItemStack prototype = passes(effective.viewRequirement()) ? effective.render(viewer) : null;
        for (int slot : item.slots()) {
            if (slot >= target.getSize() || binds.containsKey(slot) || pagedSlots.contains(slot)) {
                continue;
            }
            target.setItem(slot, prototype == null ? null : stamp(prototype.clone(), slot));
        }
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
        int[] slots = bind.slots();
        pagedPhs.clear();
        GuiItemDef item = bind.template().item();
        for (int index = 0; index < slots.length; index++) {
            int slot = slots[index];
            if (slot < 0 || slot >= target.getSize() || binds.containsKey(slot)) {
                continue;
            }
            ItemStack stack = null;
            if (index < slice.size()) {
                PhCollector collector = new PhCollector();
                bind.mapper().accept(slice.get(index), collector);
                Ph[] phs = collector.toArray();
                if (item.viewRequirement().test(viewer, resolver(phs))) {
                    stack = stamp(bind.template().render(viewer, phs), slot);
                    pagedPhs.put(slot, phs);
                }
            }
            target.setItem(slot, stack);
        }
    }

    private void renderBinding(Inventory target, int slot, Binding binding) {
        Requirement viewReq = binding.template().item().viewRequirement();
        ItemStack stack = null;
        if (viewReq.test(viewer, resolver(binding.phs()))) {
            stack = stamp(binding.template().render(viewer, binding.phs()), slot);
        }
        target.setItem(slot, stack);
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
                        new ActionContext(viewer, ctx, this, null, new Ph[0]));
            });
        } catch (IllegalPluginAccessException e) {
            ctx.debug().log(() -> "close-actions de '" + def.id()
                    + "' descartadas: owner deshabilitado");
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

    /** Template bound to a slot with its local placeholders captured at bind time. */
    private record Binding(GuiTemplate template, Ph[] phs) {
    }

    /** Live paged bind: template, immutable pagination snapshot, target slots and mapper. */
    private record PagedBind<T>(GuiTemplate template, Pagination<T> pagination, int[] slots,
                                BiConsumer<T, PhCollector> mapper) {
    }
}
