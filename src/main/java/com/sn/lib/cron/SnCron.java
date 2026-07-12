package com.sn.lib.cron;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.yml.SnYml;

/**
 * Calendar scheduler of a consumer context, reached through {@code sn.cron()}.
 *
 * <p>Each job pairs an id with a {@link CronExpr} (5-field subset or the
 * {@code daily}/{@code hourly} shortcuts) and a task run on the main thread at every
 * matching instant: the delay to the next run is computed and scheduled through the
 * context's Folia-aware scheduler, and the job re-schedules itself after every run, so
 * wall-clock drift never accumulates. Jobs live in a tenant registry keyed by the owning
 * plugin: a disable sweeps them even when the owner never cancelled.</p>
 *
 * <p>Catch-up: a job scheduled through {@link Builder#catchUp catchUp(true)} persists its
 * last-run instant to the {@code cron-data.yml} data file of the owning plugin and, when
 * scheduled again (typically the next startup), fires ONCE immediately if a run was
 * missed while offline. The last-run write goes through {@link SnYml#save()}, which flips
 * to a synchronous write during the context teardown, so a run recorded while shutting
 * down is never lost. Catch-up requires the yml module; without it the job still runs but
 * WARNs once and nothing persists.</p>
 */
public final class SnCron {

    /** Server-wide static justified: jobs keyed per owning plugin for the sweep. */
    private static final TenantRegistry<Job> JOBS = new TenantRegistry<>(SnCron::sweep);

    private static final String DATA_FILE = "cron-data.yml";
    private static final String LAST_RUN_PREFIX = "last-run.";

    private final Sn ctx;
    private final Map<String, Job> byId = new ConcurrentHashMap<>();

    private final Object dataLock = new Object();
    private @Nullable SnYml data;
    private boolean dataUnavailable;

    public SnCron(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Schedules {@code task} under {@code id} at every instant matched by {@code expr},
     * replacing any previous job with the same id. An invalid expression WARNs and
     * schedules nothing.
     */
    public void schedule(String id, String expr, Runnable task) {
        create(id, expr).schedule(task);
    }

    /** Starts a job definition; nothing is scheduled until {@link Builder#schedule}. */
    public Builder create(String id, String expr) {
        return new Builder(id, expr);
    }

    /** Cancels the job and forgets its id; unknown ids no-op. */
    public void cancel(String id) {
        Job job = byId.remove(id);
        if (job != null) {
            JOBS.remove(ctx.plugin(), job);
            sweep(job);
        }
    }

    private void scheduleJob(String id, String expr, boolean catchUp, Runnable task) {
        CronExpr parsed;
        try {
            parsed = CronExpr.parse(expr);
        } catch (IllegalArgumentException e) {
            ctx.plugin().getLogger().warning("Cron job '" + id + "' not scheduled: "
                    + e.getMessage());
            return;
        }
        Job job = new Job(id, parsed, task, catchUp);
        Job previous = byId.put(id, job);
        if (previous != null) {
            JOBS.remove(ctx.plugin(), previous);
            sweep(previous);
        }
        JOBS.add(ctx.plugin(), job);
        if (catchUp) {
            catchUpIfMissed(job);
        }
        scheduleNext(job);
    }

    /** Arms the next run: computes the delay to the expression's next instant and schedules it. */
    private void scheduleNext(Job job) {
        if (job.cancelled || ctx.isShuttingDown()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = job.expr.nextRun(now);
        long delayTicks = Math.max(1L, (Duration.between(now, next).toMillis() + 49L) / 50L);
        try {
            job.handle = ctx.scheduler().syncLater(delayTicks, () -> runJob(job));
        } catch (IllegalPluginAccessException e) {
            // Owner disabled while arming the next run; the job simply stops.
        }
    }

    private void runJob(Job job) {
        if (job.cancelled || ctx.isShuttingDown()) {
            return;
        }
        recordRun(job);
        try {
            job.task.run();
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Cron job '" + job.id + "' threw an error: " + t);
        }
        scheduleNext(job);
    }

    /**
     * Fires the missed run once when the persisted last run already had a due instant in
     * the past. A job with no persisted last run only records the current instant as its
     * baseline: a fresh install never fires retroactively.
     */
    private void catchUpIfMissed(Job job) {
        SnYml yml = dataFile();
        if (yml == null) {
            return;
        }
        long lastMillis = yml.getLong(LAST_RUN_PREFIX + job.id, 0L);
        ZonedDateTime now = ZonedDateTime.now();
        if (lastMillis <= 0L) {
            recordRun(job);
            return;
        }
        ZonedDateTime last = Instant.ofEpochMilli(lastMillis).atZone(now.getZone());
        if (!job.expr.nextRun(last).isAfter(now)) {
            try {
                ctx.scheduler().sync(() -> runMissed(job));
            } catch (IllegalPluginAccessException e) {
                // Owner disabled while arming the catch-up; nothing to recover.
            }
        }
    }

    private void runMissed(Job job) {
        if (job.cancelled || ctx.isShuttingDown()) {
            return;
        }
        recordRun(job);
        try {
            job.task.run();
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning(
                    "Cron job '" + job.id + "' threw an error in the catch-up: " + t);
        }
    }

    /** Persists the run instant; synchronous during teardown via the shuttingDown flag. */
    private void recordRun(Job job) {
        if (!job.catchUp) {
            return;
        }
        SnYml yml = dataFile();
        if (yml == null) {
            return;
        }
        yml.set(LAST_RUN_PREFIX + job.id, System.currentTimeMillis());
        yml.save();
    }

    /** Lazily mounts the data file; null (after one WARN) when the yml module is absent. */
    private @Nullable SnYml dataFile() {
        synchronized (dataLock) {
            if (data == null && !dataUnavailable) {
                try {
                    data = ctx.yml().data(DATA_FILE);
                } catch (UnsupportedOperationException e) {
                    dataUnavailable = true;
                    ctx.plugin().getLogger().warning("catchUp(true) requires the yml module"
                            + " (SnSpec.builder().config(...)): the last-run does not persist");
                }
            }
            return data;
        }
    }

    /** Full release of one job: marked cancelled and its pending handle cancelled. */
    private static void sweep(Job job) {
        job.cancelled = true;
        TaskHandle handle = job.handle;
        job.handle = null;
        if (handle != null) {
            try {
                handle.cancel();
            } catch (Throwable ignored) {
                // Scheduler already gone during shutdown; nothing left to cancel.
            }
        }
    }

    /** Job definition builder returned by {@link #create}. */
    public final class Builder {

        private final String id;
        private final String expr;
        private boolean catchUp;

        private Builder(String id, String expr) {
            this.id = id;
            this.expr = expr;
        }

        /** Persists the last run and fires one missed run on re-schedule (default false). */
        public Builder catchUp(boolean catchUp) {
            this.catchUp = catchUp;
            return this;
        }

        /** Registers and arms the job, replacing any previous job with the same id. */
        public void schedule(Runnable task) {
            scheduleJob(id, expr, catchUp, task);
        }
    }

    private static final class Job {

        final String id;
        final CronExpr expr;
        final Runnable task;
        final boolean catchUp;
        volatile boolean cancelled;
        volatile @Nullable TaskHandle handle;

        Job(String id, CronExpr expr, Runnable task, boolean catchUp) {
            this.id = id;
            this.expr = expr;
            this.task = task;
            this.catchUp = catchUp;
        }
    }
}
