package com.sn.lib.velocity;

import java.time.Duration;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

/**
 * Thin helper over Velocity's scheduler, reached via {@link Snv#scheduler()}. Removes the
 * {@code buildTask(...).schedule()} boilerplate; every method returns the
 * {@link ScheduledTask} so the caller can {@code cancel()} it. For anything richer, use
 * {@code snv.proxy().getScheduler()} directly.
 */
public final class SnvScheduler {

    private final Object plugin;
    private final ProxyServer proxy;

    SnvScheduler(Object plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    /** Runs {@code task} as soon as possible on the scheduler. */
    public ScheduledTask run(Runnable task) {
        return proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    /** Runs {@code task} once after {@code delay}. */
    public ScheduledTask later(Runnable task, Duration delay) {
        return proxy.getScheduler().buildTask(plugin, task).delay(delay).schedule();
    }

    /** Runs {@code task} every {@code interval}, starting one interval from now. */
    public ScheduledTask repeat(Runnable task, Duration interval) {
        return proxy.getScheduler().buildTask(plugin, task).repeat(interval).schedule();
    }

    /** Runs {@code task} every {@code interval}, first after {@code delay}. */
    public ScheduledTask repeat(Runnable task, Duration delay, Duration interval) {
        return proxy.getScheduler().buildTask(plugin, task).delay(delay).repeat(interval).schedule();
    }
}
