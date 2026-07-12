package com.sn.lib.bossbar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.text.SnText;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBarViewer;

/**
 * Boss bar service of a consumer context, reached through {@code sn.bossbars()}.
 *
 * <p>Bars are Adventure {@link BossBar} instances shown per player through the Audience
 * API (zero packets). Titles render through the SnText pipeline ({@code [rgb]} and
 * {@code [center]} included). A quitting or kicked player is dropped from every bar of
 * the context automatically, and bar entries live in a tenant registry keyed by the
 * owning plugin, so a disable sweeps them even when the owner never cleaned up; the
 * context teardown calls {@link #hideAll()}.</p>
 */
public final class BossBarUtil {

    /** Server-wide static justified: bar entries keyed per owning plugin for the sweep. */
    private static final TenantRegistry<BarEntry> BARS =
            new TenantRegistry<>(BossBarUtil::sweep);

    private final Sn ctx;
    private final Map<String, BarEntry> byId = new ConcurrentHashMap<>();
    private final Set<String> warnedIds = ConcurrentHashMap.newKeySet();

    public BossBarUtil(Sn ctx) {
        this.ctx = ctx;
        QuitCleanupListener.register(ctx.plugin(), this::dropViewer);
    }

    /** Starts a bar definition under {@code id}; nothing registers until {@link Builder#build()}. */
    public Builder create(String id) {
        return new Builder(this, id);
    }

    /** True when a bar is registered under {@code id}; never WARNs (unlike a get()). */
    public boolean exists(String id) {
        return byId.containsKey(id);
    }

    /** Shows the bar to the viewer; unknown ids WARN once and no-op. */
    public void show(Player viewer, String id) {
        BarEntry entry = get(id);
        if (entry != null && viewer != null) {
            viewer.showBossBar(entry.bar);
        }
    }

    /** Hides the bar from the viewer only; other viewers keep it. */
    public void hide(Player viewer, String id) {
        BarEntry entry = get(id);
        if (entry != null && viewer != null) {
            viewer.hideBossBar(entry.bar);
        }
    }

    /** Re-renders the bar title through the SnText pipeline. */
    public void setText(String id, String text) {
        BarEntry entry = get(id);
        if (entry != null) {
            entry.bar.name(SnText.color(text));
        }
    }

    /** Sets the bar progress, clamped to 0..1; a running timer overwrites it next tick. */
    public void setProgress(String id, float progress) {
        BarEntry entry = get(id);
        if (entry != null) {
            entry.bar.progress(clamp(progress));
        }
    }

    /**
     * Animates the bar progress linearly across {@code duration}: with {@code countdown}
     * true it drains from 1 to 0, otherwise it fills from 0 to 1. A new timer replaces the
     * previous one; when the duration elapses the timer stops and the bar stays visible at
     * its final progress until hidden.
     */
    public void timer(String id, Duration duration, boolean countdown) {
        BarEntry entry = get(id);
        if (entry == null) {
            return;
        }
        cancelTimer(entry);
        long totalMillis = Math.max(50L, duration == null ? 50L : duration.toMillis());
        long start = System.currentTimeMillis();
        entry.bar.progress(countdown ? 1.0f : 0.0f);
        entry.timer = ctx.scheduler().timer(1L, 2L, () -> {
            double fraction =
                    Math.min(1.0, (System.currentTimeMillis() - start) / (double) totalMillis);
            entry.bar.progress(clamp((float) (countdown ? 1.0 - fraction : fraction)));
            if (fraction >= 1.0) {
                cancelTimer(entry);
            }
        });
    }

    /** Stops the bar's running timer, if any; the current progress is kept. */
    public void cancelTimer(String id) {
        BarEntry entry = get(id);
        if (entry != null) {
            cancelTimer(entry);
        }
    }

    /** Hides the bar from every viewer, stops its timer and unregisters the id. */
    public void remove(String id) {
        BarEntry entry = byId.remove(id);
        if (entry == null) {
            return;
        }
        BARS.remove(ctx.plugin(), entry);
        sweep(entry);
    }

    /**
     * Hides every bar of this context from all viewers and stops their timers; bars stay
     * registered and can be re-shown. The context teardown calls this before releasing
     * the owner's registrations.
     */
    public void hideAll() {
        for (BarEntry entry : byId.values()) {
            sweep(entry);
        }
    }

    private BossBar register(String id, BossBar bar) {
        BarEntry entry = new BarEntry(bar);
        BarEntry previous = byId.put(id, entry);
        if (previous != null) {
            BARS.remove(ctx.plugin(), previous);
            sweep(previous);
        }
        BARS.add(ctx.plugin(), entry);
        return bar;
    }

    private @Nullable BarEntry get(String id) {
        BarEntry entry = byId.get(id);
        if (entry == null && warnedIds.add(id)) {
            ctx.plugin().getLogger().warning("Unknown bossbar '" + id
                    + "': the operation is ignored (missing create(\"" + id + "\").build())");
        }
        return entry;
    }

    /** Quit/kick cleanup: drops the player from every bar of this context. */
    private void dropViewer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        for (BarEntry entry : byId.values()) {
            if (player != null) {
                player.hideBossBar(entry.bar);
            } else {
                removeViewer(entry.bar, uuid);
            }
        }
    }

    private static void removeViewer(BossBar bar, UUID uuid) {
        for (Audience viewer : viewersOf(bar)) {
            if (viewer instanceof Player player && player.getUniqueId().equals(uuid)) {
                bar.removeViewer(viewer);
            }
        }
    }

    /** Full release of one entry: timer cancelled, every viewer removed. */
    private static void sweep(BarEntry entry) {
        cancelTimer(entry);
        for (Audience viewer : viewersOf(entry.bar)) {
            entry.bar.removeViewer(viewer);
        }
    }

    private static void cancelTimer(BarEntry entry) {
        TaskHandle timer = entry.timer;
        entry.timer = null;
        if (timer != null) {
            try {
                timer.cancel();
            } catch (Throwable ignored) {
                // Scheduler already gone during shutdown; nothing left to cancel.
            }
        }
    }

    /** Snapshot of the bar's viewers as audiences, safe to remove while iterating. */
    private static List<Audience> viewersOf(BossBar bar) {
        List<Audience> viewers = new ArrayList<>();
        for (BossBarViewer viewer : bar.viewers()) {
            if (viewer instanceof Audience audience) {
                viewers.add(audience);
            }
        }
        return viewers;
    }

    private static float clamp(float progress) {
        return Math.max(0.0f, Math.min(1.0f, progress));
    }

    /** Bar definition builder returned by {@link #create}. */
    public static final class Builder {

        private final BossBarUtil util;
        private final String id;
        private String text = "";
        private float progress = 1.0f;
        private BossBar.Color color = BossBar.Color.WHITE;
        private BossBar.Overlay overlay = BossBar.Overlay.PROGRESS;

        private Builder(BossBarUtil util, String id) {
            this.util = util;
            this.id = id;
        }

        /** Bar title, rendered through the SnText pipeline ({@code [rgb]} included). */
        public Builder text(String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        /** Initial progress, clamped to 0..1 (default 1). */
        public Builder progress(float progress) {
            this.progress = clamp(progress);
            return this;
        }

        /** Bar color (default WHITE); null keeps the default. */
        public Builder color(BossBar.Color color) {
            if (color != null) {
                this.color = color;
            }
            return this;
        }

        /** Bar overlay (default PROGRESS); null keeps the default. */
        public Builder overlay(BossBar.Overlay overlay) {
            if (overlay != null) {
                this.overlay = overlay;
            }
            return this;
        }

        /**
         * Builds and registers the bar under its id, replacing (and hiding) any previous
         * bar with the same id. The bar starts with no viewers; use {@link #show}.
         */
        public BossBar build() {
            BossBar bar = BossBar.bossBar(SnText.color(text), progress, color, overlay);
            return util.register(id, bar);
        }
    }

    private static final class BarEntry {

        final BossBar bar;
        volatile @Nullable TaskHandle timer;

        BarEntry(BossBar bar) {
            this.bar = bar;
        }
    }
}
