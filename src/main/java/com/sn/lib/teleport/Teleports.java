package com.sn.lib.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.lang.SnLang;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.text.SnText;

/**
 * Warmup teleport module of one Sn context, reached through {@code sn.teleports()}.
 *
 * <p>Opt-in: available only when the spec declares {@code teleports()}. It solves the
 * warmup teleport every {@code /home} {@code /warp} {@code /rally} re-implements: one
 * pending teleport per player (dedup), a warmup message at start, cancel on movement and
 * on damage, an optional cooldown category shared with {@code sn.cooldowns()}, and a
 * Folia-safe completion.</p>
 *
 * <p>State machine of a request: dedup first (a second request while one is pending is
 * rejected with {@link TeleportResult#ALREADY_PENDING}, never double-scheduled), then the
 * optional cooldown gate ({@link TeleportResult#ON_COOLDOWN}), then either an immediate
 * dispatch ({@link TeleportResult#TELEPORTED}, no warmup) or a scheduled warmup
 * ({@link TeleportResult#WARMUP_STARTED}). The decision is the pure {@link #evaluate}.</p>
 *
 * <p>Cancellation is driven by two shared listeners (one {@code PlayerMoveEvent} with a
 * block-delta quick exit that ignores head rotation, one {@code EntityDamageEvent} at
 * MONITOR) that only ever act for owners that declared this module: a context without the
 * module registers no manager here, so nothing runs for it. A pending teleport is also
 * cancelled on quit/kick and on the context teardown/reload; every {@link TaskHandle} is
 * tracked and cancelled, so nothing leaks.</p>
 *
 * <p>Completion teleports through {@code Player#teleportAsync}, the region-safe call that
 * works the same on Paper and Folia; the optional {@code onComplete} callback runs on the
 * main thread after a successful teleport only.</p>
 */
public final class Teleports {

    /**
     * Server-wide static justified (SelectionManager pattern): the two shared listeners
     * resolve every manager with a pending teleport, and the sweep callback is the double
     * safety net for an owner that never shut down. Per-plugin state lives inside each
     * instance, never in this registry.
     */
    private static final TenantRegistry<Teleports> MANAGERS =
            new TenantRegistry<>(Teleports::shutdownQuietly);

    /** Server-wide static justified: constant defaults mirroring snlib-messages.yml. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            TeleportOptions.DEFAULT_WARMUP_KEY, "&7Teleporting in &e{time}&7. Do not move.",
            TeleportOptions.DEFAULT_CANCELLED_MOVE_KEY, "&cTeleport cancelled - you moved.",
            TeleportOptions.DEFAULT_CANCELLED_DAMAGE_KEY, "&cTeleport cancelled - you took damage.");

    private final Sn ctx;
    private final Map<UUID, PendingTeleport> pendings = new ConcurrentHashMap<>();

    /** Wires the module for a context: tenant registration plus quit cleanup. */
    public Teleports(Sn ctx) {
        this.ctx = ctx;
        MANAGERS.add(ctx.plugin(), this);
        QuitCleanupListener.register(ctx.plugin(), this::onQuit);
    }

    /**
     * Requests a warmup teleport of the player to the target with the default options
     * ({@link TeleportOptions#instant()}: no warmup, no cooldown). Convenience for a plain
     * immediate teleport that still flows through the module's bookkeeping.
     */
    public TeleportResult request(Player player, Location target) {
        return request(player, target, TeleportOptions.instant());
    }

    /**
     * Requests a warmup teleport of the player to the target. Main-thread only.
     *
     * <ul>
     *   <li>a pending teleport already exists for the player -&gt; {@link TeleportResult#ALREADY_PENDING};</li>
     *   <li>the options declare a cooldown category still running -&gt; {@link TeleportResult#ON_COOLDOWN};</li>
     *   <li>{@code warmupSeconds == 0} -&gt; the teleport is dispatched now, {@link TeleportResult#TELEPORTED};</li>
     *   <li>otherwise the warmup message is sent and the teleport is scheduled, {@link TeleportResult#WARMUP_STARTED}.</li>
     * </ul>
     *
     * A null player, null target or unloaded target world yields {@link TeleportResult#FAILED}.
     */
    public TeleportResult request(Player player, Location target, TeleportOptions opts) {
        if (player == null || target == null || !target.isWorldLoaded()) {
            return TeleportResult.FAILED;
        }
        if (opts == null) {
            opts = TeleportOptions.instant();
        }
        UUID id = player.getUniqueId();
        boolean alreadyPending = pendings.containsKey(id);
        String category = opts.cooldownCategory();
        boolean onCooldown = !alreadyPending && category != null
                && ctx.cooldowns().remainingMillis(id, category) > 0L;
        TeleportResult decision = evaluate(alreadyPending, onCooldown, opts.warmupSeconds());
        switch (decision) {
            case TELEPORTED -> performTeleport(player, target.clone(), opts);
            case WARMUP_STARTED -> startWarmup(player, target.clone(), opts);
            default -> {
                // ALREADY_PENDING, ON_COOLDOWN, FAILED: no side effect, the caller messages.
            }
        }
        return decision;
    }

    /**
     * Pure state machine of a request in priority order: dedup wins over cooldown, and a
     * zero warmup means an instant teleport. Extracted for unit coverage without a server.
     */
    static TeleportResult evaluate(boolean alreadyPending, boolean onCooldown, int warmupSeconds) {
        if (alreadyPending) {
            return TeleportResult.ALREADY_PENDING;
        }
        if (onCooldown) {
            return TeleportResult.ON_COOLDOWN;
        }
        return warmupSeconds <= 0 ? TeleportResult.TELEPORTED : TeleportResult.WARMUP_STARTED;
    }

    /** Whether the player currently has a pending (warming-up) teleport of this context. */
    public boolean isPending(Player player) {
        return player != null && isPending(player.getUniqueId());
    }

    /** Whether the player id currently has a pending teleport of this context. */
    public boolean isPending(UUID playerId) {
        return playerId != null && pendings.containsKey(playerId);
    }

    /** See {@link #cancel(UUID)}. */
    public boolean cancel(Player player) {
        return player != null && cancel(player.getUniqueId());
    }

    /**
     * Cancels the player's pending teleport WITHOUT sending a message; returns whether one
     * was pending. Idempotent: without a pending teleport it is a silent no-op.
     */
    public boolean cancel(UUID playerId) {
        PendingTeleport pending = pendings.remove(playerId);
        if (pending == null) {
            return false;
        }
        stopTask(pending);
        return true;
    }

    /**
     * Tears the module down: cancels every warmup task (each guarded, the scheduler may be
     * dying) and clears the pending map. Idempotent; deliberately runs no {@code onComplete}
     * callback. Invoked by the context teardown (step 4 of {@code Sn.shutdown()}) and by the
     * tenant sweep as a double safety net.
     */
    public void shutdown() {
        for (PendingTeleport pending : pendings.values()) {
            stopTask(pending);
        }
        pendings.clear();
    }

    // ------------------------------------------------------------------
    // Listener bridges (called by the shared teleport listeners)
    // ------------------------------------------------------------------

    /**
     * Internal bridge for the shared move listener: cancels the player's pending teleport
     * in every declared manager with the move message. Not part of the consumer contract.
     */
    public static void dispatchMove(Player player) {
        UUID id = player.getUniqueId();
        MANAGERS.forEachOwner((owner, managers) -> {
            for (Teleports manager : managers) {
                manager.cancelPending(id, true);
            }
        });
    }

    /**
     * Internal bridge for the shared damage listener: cancels the player's pending teleport
     * in every declared manager with the damage message. Not part of the consumer contract.
     */
    public static void dispatchDamage(Player player) {
        UUID id = player.getUniqueId();
        MANAGERS.forEachOwner((owner, managers) -> {
            for (Teleports manager : managers) {
                manager.cancelPending(id, false);
            }
        });
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void startWarmup(Player player, Location target, TeleportOptions opts) {
        UUID id = player.getUniqueId();
        PendingTeleport pending = new PendingTeleport(target, opts);
        // Main-thread only, so containsKey above and this put form a stable dedup; the
        // scheduled completion cannot run before this method returns control to the scheduler.
        pendings.put(id, pending);
        pending.task = ctx.scheduler().syncLater(opts.warmupTicks(), () -> complete(id));
        message(player, opts.warmupKey(), opts.silent(), Ph.of("time", opts.warmupSeconds()));
    }

    private void complete(UUID id) {
        PendingTeleport pending = pendings.remove(id);
        if (pending == null) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) {
            return;
        }
        performTeleport(player, pending.target, pending.opts);
    }

    private void performTeleport(Player player, Location target, TeleportOptions opts) {
        UUID id = player.getUniqueId();
        String category = opts.cooldownCategory();
        if (category != null && opts.cooldownSeconds() > 0) {
            ctx.cooldowns().tryUseTicks(id, category, opts.cooldownSeconds() * 20L);
        }
        Consumer<Player> onComplete = opts.onComplete();
        player.teleportAsync(target).whenComplete((success, error) -> {
            if (error != null) {
                ctx.plugin().getLogger().warning("Async teleport failed: " + error);
                return;
            }
            if (shouldRunOnComplete(onComplete, success)) {
                runOnComplete(id, onComplete);
            }
        });
    }

    /**
     * Pure gate for the completion callback: it runs only after a genuinely successful
     * teleport ({@code success == Boolean.TRUE}), never a teleport another plugin vetoed
     * ({@code success == false}) nor one that completed exceptionally ({@code success == null}).
     * Extracted for unit coverage without a server.
     */
    static boolean shouldRunOnComplete(@Nullable Consumer<Player> onComplete, @Nullable Boolean success) {
        return onComplete != null && Boolean.TRUE.equals(success);
    }

    private void runOnComplete(UUID id, Consumer<Player> onComplete) {
        ctx.scheduler().sync(() -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                return;
            }
            try {
                onComplete.accept(online);
            } catch (Throwable t) {
                ctx.plugin().getLogger().warning("onComplete callback of a teleport failed: " + t);
            }
        });
    }

    /** Cancels a pending teleport and sends the move or damage cancel message. */
    private void cancelPending(UUID id, boolean move) {
        PendingTeleport pending = pendings.remove(id);
        if (pending == null) {
            return;
        }
        stopTask(pending);
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            String key = move ? pending.opts.cancelledMoveKey() : pending.opts.cancelledDamageKey();
            message(player, key, pending.opts.silent());
        }
    }

    /** Quit/kick cleanup callback; idempotent because a kick fires kick and quit. */
    private void onQuit(UUID id) {
        cancel(id);
    }

    /**
     * Sends a teleport message unless the request is silent: the lang module when declared,
     * the embedded English default otherwise (the module never requires lang).
     */
    private void message(Player player, String key, boolean silent, Ph... phs) {
        if (silent) {
            return;
        }
        SnLang lang = langOrNull();
        if (lang != null) {
            lang.send(player, key, phs);
            return;
        }
        String template = DEFAULT_MESSAGES.get(key);
        if (template == null) {
            template = "<missing:" + key + ">";
        }
        player.sendMessage(SnText.color(SnText.applyLocals(template, phs)));
    }

    private @Nullable SnLang langOrNull() {
        try {
            return ctx.lang();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    private static void stopTask(PendingTeleport pending) {
        TaskHandle task = pending.task;
        if (task == null) {
            return;
        }
        pending.task = null;
        try {
            task.cancel();
        } catch (Throwable t) {
            // The scheduler may be dying during teardown; a failed cancel is harmless.
        }
    }

    private static void shutdownQuietly(Teleports manager) {
        try {
            manager.shutdown();
        } catch (Throwable t) {
            manager.ctx.plugin().getLogger().warning("Teleport module shutdown failed: " + t);
        }
    }

    /** One pending warmup teleport: the cloned target, its options and the warmup task. */
    private static final class PendingTeleport {

        private final Location target;
        private final TeleportOptions opts;
        private volatile @Nullable TaskHandle task;

        private PendingTeleport(Location target, TeleportOptions opts) {
            this.target = target;
            this.opts = opts;
        }
    }
}
