package com.sn.lib.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.sn.lib.compat.SnVersion;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Folia-aware task scheduler bound to one owning plugin (one instance per {@code Sn}
 * context).
 *
 * <p>Folia support is detection plus no-crash: when {@link SnVersion#isFolia()} is true,
 * tasks go through the global region and async schedulers so scheduling never throws.
 * It is NOT a full region-aware port; the GUI and item modules remain Paper-only.</p>
 */
public final class SnScheduler {

    private final JavaPlugin plugin;

    public SnScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs on the main thread (global region on Folia). */
    public TaskHandle sync(Runnable task) {
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run()));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTask(plugin, task));
    }

    /** Runs off the main thread. */
    public TaskHandle async(Runnable task) {
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run()));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    /** Runs on the main thread after {@code delayTicks} (minimum 1). */
    public TaskHandle syncLater(long delayTicks, Runnable task) {
        long delay = Math.max(1L, delayTicks);
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getGlobalRegionScheduler()
                    .runDelayed(plugin, t -> task.run(), delay));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
    }

    /** Runs off the main thread after {@code delayTicks} (minimum 1). */
    public TaskHandle asyncLater(long delayTicks, Runnable task) {
        long delay = Math.max(1L, delayTicks);
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getAsyncScheduler()
                    .runDelayed(plugin, t -> task.run(), delay * 50L, TimeUnit.MILLISECONDS));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay));
    }

    /** Repeats on the main thread; delay and period in ticks (minimum 1). */
    public TaskHandle timer(long delayTicks, long periodTicks, Runnable task) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> task.run(), delay, period));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
    }

    /** Repeats off the main thread; delay and period in ticks (minimum 1). */
    public TaskHandle timerAsync(long delayTicks, long periodTicks, Runnable task) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (SnVersion.isFolia()) {
            return new FoliaHandle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    t -> task.run(), delay * 50L, period * 50L, TimeUnit.MILLISECONDS));
        }
        return new BukkitHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period));
    }

    /**
     * Computes a value off the main thread. A supplier failure (or scheduling against an
     * already disabled plugin) completes the future exceptionally instead of throwing.
     */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            async(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (IllegalPluginAccessException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Consumes the future's value hopping to the main thread. The hop is skipped when the
     * owning plugin is already disabled, and the disable race inside the scheduler is
     * absorbed by catching {@link IllegalPluginAccessException}; an exceptional completion
     * logs one WARN and never reaches the consumer.
     */
    public <T> void thenSync(CompletableFuture<T> future, Consumer<T> consumer) {
        future.whenComplete((value, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Tarea async termino con error: " + error);
                return;
            }
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                sync(() -> consumer.accept(value));
            } catch (IllegalPluginAccessException e) {
                plugin.getLogger().warning(
                        "Hop al main descartado: plugin deshabilitado durante el scheduling");
            }
        });
    }

    /** Cancels every task scheduled by the owning plugin. */
    public void cancelAll() {
        if (SnVersion.isFolia()) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private record FoliaHandle(ScheduledTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }

    private record BukkitHandle(BukkitTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }
}
