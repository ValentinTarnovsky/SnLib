package com.sn.lib.region;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.event.SnSelectionCompleteEvent;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.item.SnItem;
import com.sn.lib.lang.SnLang;
import com.sn.lib.region.internal.SelectionRenderer;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.text.SnText;
import com.sn.lib.util.InvUtil;
import com.sn.lib.util.TagIo;

/**
 * Cuboid selection module of one Sn context: registers {@link SelectionSpec}s, hands out
 * tagged wand items and owns one {@link SelectionSession} per selecting player.
 *
 * <p>Always available via {@code sn.selections()} (no spec gate, like {@code actions()} or
 * {@code cooldowns()}): the module is 100% programmatic and its idle cost is an empty map
 * plus one quit-cleanup registration. Session mutations are main-thread only.</p>
 *
 * <p>Reload policy: a config reload NEVER touches selections (they are transient player
 * state, not derived from files; a reload mid-selection must not steal the selection from
 * an admin) and renderers keep running. When a consumer rebuilds specs from YML in its
 * Reloadable, {@link #registerSpec} replaces by id and live sessions keep pointing at the
 * previous immutable spec until the next {@code begin} or wand click of another spec.</p>
 *
 * <p>{@link #shutdown()} is invoked by the context teardown (step 4 of {@code Sn.shutdown()})
 * and, as a double safety net, by the tenant sweep of an owner that never shut down.</p>
 */
public final class SelectionManager {

    /** PDC key name carrying the spec id of a wand; namespaced per owner by {@link TagIo}. */
    public static final String WAND_TAG = "snlib_selection_wand";

    /**
     * Server-wide static justified (ItemPropertyListener.track pattern): (a) the shared
     * wand listener resolves the manager owning a wand by the namespace of its PDC key,
     * (b) the sweep callback is the double safety net when an owner never shut down.
     */
    private static final TenantRegistry<SelectionManager> MANAGERS =
            new TenantRegistry<>(SelectionManager::shutdownQuietly);

    /** Server-wide static justified: constant default templates mirroring snlib-messages.yml. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("snlib.selection.pos1-set", "&aPos1 set: &f{x}, {y}, {z} &7({world})"),
            Map.entry("snlib.selection.pos2-set", "&aPos2 set: &f{x}, {y}, {z} &7({world})"),
            Map.entry("snlib.selection.different-worlds", "&cBoth positions must be in the same world."),
            Map.entry("snlib.selection.too-big", "&cSelection too big: &f{volume} &7(max {max})"),
            Map.entry("snlib.selection.no-permission", "&cYou cannot use this wand."),
            Map.entry("snlib.selection.timeout", "&7Selection expired."));

    private final Sn ctx;
    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SelectionSpec> specs = new ConcurrentHashMap<>();

    /** Wires the module for a context: tenant registration plus quit cleanup. */
    public SelectionManager(Sn ctx) {
        this.ctx = ctx;
        MANAGERS.add(ctx.plugin(), this);
        QuitCleanupListener.register(ctx.plugin(), this::onQuit);
    }

    /**
     * Opens a new session for the player with the given spec, registering the spec by id.
     * A previous session of THIS context is cancelled first (renderer cut, removal and the
     * previous spec's {@code onCancel} with the UUID; no event, no message). Does NOT hand
     * a wand (compose with {@link #giveWand}). Main-thread only.
     */
    public SelectionSession begin(Player player, SelectionSpec spec) {
        registerSpec(spec);
        UUID playerId = player.getUniqueId();
        cancel(playerId);
        SelectionSession session = new SelectionSession(playerId, spec, this);
        sessions.put(playerId, session);
        return session;
    }

    /** Active session of this context for the player, or null. */
    public @Nullable SelectionSession current(Player player) {
        return current(player.getUniqueId());
    }

    /** Active session of this context for the player id, or null. */
    public @Nullable SelectionSession current(UUID playerId) {
        return sessions.get(playerId);
    }

    /** See {@link #cancel(UUID)}. */
    public void cancel(Player player) {
        cancel(player.getUniqueId());
    }

    /**
     * Cuts the renderer, removes the session and runs the spec's {@code onCancel} with the
     * UUID. Idempotent: without a session it is a silent no-op.
     */
    public void cancel(UUID playerId) {
        SelectionSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        stopRenderer(session);
        Consumer<UUID> onCancel = session.spec().onCancel();
        if (onCancel != null) {
            try {
                onCancel.accept(playerId);
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning("Callback onCancel del spec de seleccion '"
                        + session.spec().id() + "' fallo: " + t);
            }
        }
    }

    /** Cuts the renderer and removes the session WITHOUT onCancel (completeEnds, offline owner). */
    void cancelSilently(UUID playerId) {
        SelectionSession session = sessions.remove(playerId);
        if (session != null) {
            stopRenderer(session);
        }
    }

    /**
     * Registers (or replaces) the spec under its id without opening a session. Called
     * implicitly by {@link #begin} and {@link #createWand}; exists for the pattern
     * "register in onInnerEnable, hand wands later by command": an old wand sitting in an
     * inventory works again as soon as its spec is registered (the shared listener
     * auto-opens the session on the first click).
     */
    public void registerSpec(SelectionSpec spec) {
        specs.put(spec.id(), spec);
    }

    /**
     * Registers the spec and builds the physical wand: the spec's {@link SnItem} rendered
     * (or its template cloned), falling back to a BLAZE_ROD "Region Wand", tagged with the
     * spec id under {@link #WAND_TAG}. The tag value is the spec id, NOT a random UUID:
     * identical wands stack without consequences and the id resolves the spec after relog.
     */
    public ItemStack createWand(SelectionSpec spec) {
        registerSpec(spec);
        ItemStack stack;
        SnItem wandItem = spec.wandItem();
        ItemStack template = spec.wandTemplate();
        if (wandItem != null) {
            stack = wandItem.build();
        } else if (template != null) {
            stack = template.clone();
        } else {
            stack = SnItem.builder(Material.BLAZE_ROD).name("&6&lRegion Wand").build();
        }
        return TagIo.set(stack, ctx.plugin(), WAND_TAG, spec.id());
    }

    /** Creates the wand and adds it to the player's inventory (overflow drops at the feet). */
    public void giveWand(Player player, SelectionSpec spec) {
        InvUtil.giveItems(player, createWand(spec));
    }

    /** Whether the item is a selection wand of THIS context; null/air/meta-less are false. */
    public boolean isWand(@Nullable ItemStack item) {
        return TagIo.has(item, ctx.plugin(), WAND_TAG);
    }

    /** Spec id carried by a wand of this context, or null. */
    public @Nullable String wandSpecId(@Nullable ItemStack item) {
        return TagIo.get(item, ctx.plugin(), WAND_TAG);
    }

    /**
     * Tears the module down: cancels every render task (each cancel guarded, the scheduler
     * may be dying) and clears sessions and specs. Idempotent. Deliberately does NOT run
     * any {@code onCancel}: running consumer callbacks during teardown is dangerous (same
     * policy as the GUI close-actions). Invoked by the context teardown from step 4 of
     * {@code Sn.shutdown()} and by the tenant sweep as a double safety net.
     */
    public void shutdown() {
        for (SelectionSession session : sessions.values()) {
            stopRenderer(session);
        }
        sessions.clear();
        specs.clear();
    }

    /**
     * Internal bridge for the shared wand listener: manager whose owner plugin name equals
     * the PDC key namespace, or null when no live context matches (foreign item or torn
     * down owner). Not part of the consumer contract.
     */
    public static @Nullable SelectionManager forNamespace(String namespace) {
        SelectionManager[] out = new SelectionManager[1];
        MANAGERS.forEachOwner((owner, managers) -> {
            if (out[0] != null
                    || !owner.getName().toLowerCase(Locale.ROOT).equals(namespace)) {
                return;
            }
            for (SelectionManager manager : managers) {
                out[0] = manager;
                return;
            }
        });
        return out[0];
    }

    /**
     * Internal dispatch invoked by the shared wand listener after its quick-exits: gates
     * on spec registration (an unregistered id logs a debug note, no player spam) and on
     * the spec permission, auto-opens or replaces the session (what makes a wand work
     * after relog) and applies the clicked position ({@code first} = left click = pos1).
     * Not part of the consumer contract.
     */
    public void handleWandClick(Player player, String specId, boolean first, Location clicked) {
        SelectionSpec spec = specId == null ? null : specs.get(specId);
        if (spec == null) {
            ctx.debug().log(() -> "Click de wand ignorado: spec de seleccion '" + specId
                    + "' no registrado por " + ctx.plugin().getName());
            return;
        }
        if (spec.permission() != null && !player.hasPermission(spec.permission())) {
            message(player, spec, "snlib.selection.no-permission");
            return;
        }
        SelectionSession session = current(player);
        if (session == null || !session.spec().id().equals(spec.id())) {
            session = begin(player, spec);
        }
        applyPos(session, first, clicked);
    }

    /**
     * Shared pipeline of wand clicks and programmatic setters, in a fixed order: store the
     * cloned position, refresh the renderer, message the owner (pos-set, different-worlds),
     * run {@code onUpdate}, then the completion pipeline (volume cap, binding
     * {@link SnSelectionCompleteEvent}, {@code onSelect}, optional completeEnds). With the
     * owner offline the position still lands but messages, event and onSelect are skipped
     * (the renderer cancels offline sessions).
     */
    void applyPos(SelectionSession session, boolean first, @Nullable Location pos) {
        Location stored = pos == null ? null : pos.clone();
        if (first) {
            session.pos1 = stored;
        } else {
            session.pos2 = stored;
        }
        refreshRenderer(session);
        Player player = Bukkit.getPlayer(session.playerId());
        SelectionSpec spec = session.spec();
        if (player != null && stored != null) {
            message(player, spec, first ? "snlib.selection.pos1-set" : "snlib.selection.pos2-set",
                    Ph.of("x", stored.getBlockX()),
                    Ph.of("y", stored.getBlockY()),
                    Ph.of("z", stored.getBlockZ()),
                    Ph.of("world", stored.isWorldLoaded() ? stored.getWorld().getName() : "?"));
        }
        if (player != null && session.pos1 != null && session.pos2 != null
                && !session.hasBothPositions()) {
            message(player, spec, "snlib.selection.different-worlds");
        }
        Consumer<SelectionSession> onUpdate = spec.onUpdate();
        if (onUpdate != null) {
            try {
                onUpdate.accept(session);
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning("Callback onUpdate del spec de seleccion '"
                        + spec.id() + "' fallo: " + t);
            }
        }
        if (player == null || !session.hasBothPositions()) {
            return;
        }
        Cuboid cuboid = session.cuboid();
        if (cuboid == null) {
            return;
        }
        if (spec.maxVolume() > 0 && cuboid.size() > spec.maxVolume()) {
            message(player, spec, "snlib.selection.too-big",
                    Ph.of("volume", cuboid.size()), Ph.of("max", spec.maxVolume()));
            return;
        }
        if (!new SnSelectionCompleteEvent(player, ctx.plugin(), spec.id(), cuboid).call()) {
            return;
        }
        Consumer<Cuboid> onSelect = spec.onSelect();
        if (onSelect != null) {
            try {
                onSelect.accept(cuboid);
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning("Callback onSelect del spec de seleccion '"
                        + spec.id() + "' fallo: " + t);
            }
        }
        if (spec.completeEnds()) {
            cancelSilently(session.playerId());
        }
    }

    /**
     * Cuts the previous render task of the session and, when AT LEAST ONE position is
     * set (improvement over SnGens, which required both), arms the repeating
     * {@link SelectionRenderer} through the context scheduler, storing the handle in
     * the session; with no position the session keeps no task. The renderer also owns
     * the spec timeout: it exists whenever there is something to show, and a session
     * without any position never expires because it has nothing to lose.
     */
    void refreshRenderer(SelectionSession session) {
        stopRenderer(session);
        if (session.pos1 == null && session.pos2 == null) {
            return;
        }
        session.renderTask = ctx.scheduler().timer(1L, session.spec().refreshIntervalTicks(),
                new SelectionRenderer(this, session));
    }

    /**
     * Internal bridge for the session renderer (a {@code region.internal} class that
     * cannot reach the package-private members): silently discards the session of an
     * owner that is no longer online (no onCancel, no message), closing the race the
     * quit cleanup did not see so the task never runs in vain. Not part of the
     * consumer contract.
     */
    public void handleRendererOffline(UUID playerId) {
        cancelSilently(playerId);
    }

    /**
     * Internal bridge for the session renderer: expires a session whose spec timeout
     * ran out, cancelling WITH onCancel and sending {@code snlib.selection.timeout}
     * unless the spec is silent. Not part of the consumer contract.
     */
    public void handleRendererTimeout(UUID playerId) {
        SelectionSession session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        SelectionSpec spec = session.spec();
        cancel(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            message(player, spec, "snlib.selection.timeout");
        }
    }

    /**
     * Sends a selection message unless the spec is silent: the lang module when declared,
     * the embedded English default otherwise (the library never requires lang).
     */
    void message(Player target, SelectionSpec spec, String key, Ph... phs) {
        if (spec.silent()) {
            return;
        }
        SnLang lang = langOrNull();
        if (lang != null) {
            lang.send(target, key, phs);
            return;
        }
        String template = DEFAULT_MESSAGES.get(key);
        if (template == null) {
            template = "<missing:" + key + ">";
        }
        target.sendMessage(SnText.color(SnText.applyLocals(template, phs)));
    }

    /** Quit/kick cleanup callback; idempotent because a kick fires kick and quit. */
    void onQuit(UUID playerId) {
        cancel(playerId);
    }

    private void stopRenderer(SelectionSession session) {
        TaskHandle task = session.renderTask;
        if (task == null) {
            return;
        }
        session.renderTask = null;
        try {
            task.cancel();
        } catch (Throwable t) {
            // The scheduler may be dying during teardown; a failed cancel is harmless.
        }
    }

    private @Nullable SnLang langOrNull() {
        try {
            return ctx.lang();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    private static void shutdownQuietly(SelectionManager manager) {
        try {
            manager.shutdown();
        } catch (Throwable t) {
            manager.ctx.plugin().getLogger()
                    .warning("Shutdown del modulo de seleccion fallo: " + t);
        }
    }
}
