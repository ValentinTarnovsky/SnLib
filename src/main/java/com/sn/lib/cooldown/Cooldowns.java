package com.sn.lib.cooldown;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.scheduler.TaskHandle;

/**
 * Per-context cooldown store keyed by category and player.
 *
 * <p>State is {@code Map<String, Map<UUID, long[]>>} where each one-element {@code long[]}
 * cell holds the expiry epoch millis (no Long boxing on the hot path). Unexpired entries
 * are NEVER dropped when a player quits, so a relog does not reset cooldowns; the explicit
 * exception is a category registered via {@link #registerSessionCategory}, whose entries
 * are cleared on quit/kick through the quit cleanup listener. Expired entries are purged
 * lazily on read plus by an async sweep every 5 minutes, started on first use.</p>
 */
public final class Cooldowns {

    private static final long SWEEP_PERIOD_TICKS = 5L * 60L * 20L;

    private final Map<String, Map<UUID, long[]>> byCategory = new ConcurrentHashMap<>();
    private final Set<String> sessionCategories = ConcurrentHashMap.newKeySet();
    private final Sn ctx;

    private volatile boolean sweepScheduled;
    private boolean sweepWarned;
    private @Nullable TaskHandle sweepTask;

    public Cooldowns(Sn ctx) {
        this.ctx = ctx;
        QuitCleanupListener.register(ctx.plugin(), this::clearSession);
    }

    /**
     * Arms the category cooldown for the player unless it is still running.
     *
     * @return true when the action may run (cooldown armed or re-armed); false while
     *         the player is still cooling down
     */
    public boolean tryUse(UUID player, String category, Duration cooldown) {
        return tryUseMillis(player, category, cooldown.toMillis());
    }

    /** Tick-based variant of {@link #tryUse} (1 tick = 50 ms). */
    public boolean tryUseTicks(UUID player, String category, long cooldownTicks) {
        return tryUseMillis(player, category, cooldownTicks * 50L);
    }

    /** Milliseconds left on the player's cooldown; 0 when expired or never armed. */
    public long remainingMillis(UUID player, String category) {
        Map<UUID, long[]> entries = byCategory.get(category);
        if (entries == null) {
            return 0L;
        }
        long[] expiry = entries.get(player);
        if (expiry == null) {
            return 0L;
        }
        long remaining = expiry[0] - System.currentTimeMillis();
        if (remaining <= 0L) {
            entries.remove(player, expiry);
            return 0L;
        }
        return remaining;
    }

    /**
     * Marks a category as session-scoped: its entries are cleared when the player quits
     * or is kicked. Entries of every other category survive relogs by design.
     */
    public void registerSessionCategory(String category) {
        sessionCategories.add(category);
    }

    /** Drops the player's entries in every session category; persistent categories stay. */
    public void clearSession(UUID player) {
        for (String category : sessionCategories) {
            Map<UUID, long[]> entries = byCategory.get(category);
            if (entries != null) {
                entries.remove(player);
            }
        }
    }

    /** Drops every entry of every category and stops the sweep task. */
    public void clearAll() {
        TaskHandle task;
        synchronized (this) {
            task = sweepTask;
            sweepTask = null;
            sweepScheduled = false;
            sweepWarned = false;
        }
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // Scheduler already gone during shutdown; nothing left to cancel.
            }
        }
        byCategory.clear();
    }

    private boolean tryUseMillis(UUID player, String category, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return true;
        }
        ensureSweepScheduled();
        long now = System.currentTimeMillis();
        Map<UUID, long[]> entries =
                byCategory.computeIfAbsent(category, key -> new ConcurrentHashMap<>());
        long[] armed = {now + cooldownMillis};
        long[] winner = entries.compute(player,
                (uuid, expiry) -> expiry != null && expiry[0] > now ? expiry : armed);
        return winner == armed;
    }

    private void ensureSweepScheduled() {
        if (sweepScheduled || ctx.isShuttingDown()) {
            return;
        }
        synchronized (this) {
            if (sweepScheduled) {
                return;
            }
            try {
                sweepTask = ctx.scheduler()
                        .timerAsync(SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS, this::sweepExpired);
                sweepScheduled = true;
            } catch (Throwable t) {
                // Left unscheduled on purpose: the next tryUse* retries; the WARN fires once.
                if (!sweepWarned) {
                    sweepWarned = true;
                    ctx.plugin().getLogger().warning(
                            "Could not schedule the cooldown sweep; only the lazy purge remains: " + t);
                }
            }
        }
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Map<UUID, long[]> entries : byCategory.values()) {
            entries.values().removeIf(expiry -> expiry[0] <= now);
        }
    }
}
