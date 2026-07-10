package com.sn.lib.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
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
    private final List<TaskHandle> tasks = new CopyOnWriteArrayList<>();

    private volatile Inventory inventory;
    private volatile Component lastTitle;
    private volatile int page;
    private volatile boolean transitioningPage;
    private volatile boolean closed;
    private boolean typeWarned;

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
     * Definition rendered at {@code slot} for this viewer: an API bind takes precedence
     * over the declared item of that slot. Null for an empty slot.
     */
    public @Nullable GuiItemDef itemAt(int slot) {
        Binding binding = binds.get(slot);
        if (binding != null) {
            return binding.template().item();
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

    @Override
    public void nextPage() {
        if (paginationBlocked("next-page")) {
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
        page = Math.max(1, targetPage);
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
     * Closes the session: cancels its timers, unregisters it from the per-owner
     * registries, untracks the holder and force-closes the viewer's inventory if this
     * session is still on screen. Idempotent.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TaskHandle task : tasks) {
            task.cancel();
        }
        tasks.clear();
        gui.removeSession(viewer.getUniqueId(), this);
        GuiManager.SESSIONS.remove(ctx.plugin(), this);
        TenantSweeper.untrackInventory(holder);
        Inventory current = inventory;
        if (current != null && current.getViewers().contains(viewer)) {
            viewer.closeInventory();
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
                + " ignorado, pagination false (opt-in por menu)");
        return true;
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
        for (Map.Entry<Integer, Binding> entry : binds.entrySet()) {
            if (entry.getKey() < current.getSize()) {
                renderBinding(current, entry.getKey(), entry.getValue());
            }
        }
    }

    private void renderItem(Inventory target, GuiItemDef item) {
        ItemStack prototype = passes(item.viewRequirement()) ? item.render(viewer) : null;
        for (int slot : item.slots()) {
            if (slot >= target.getSize() || binds.containsKey(slot)) {
                continue;
            }
            target.setItem(slot, prototype == null ? null : stamp(prototype.clone(), slot));
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
}
