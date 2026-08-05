package com.sn.lib.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;

/**
 * Result of an asynchronous database operation.
 *
 * <p>Consumption paths: {@link #thenSync} hops the value to the main thread with an
 * is-enabled guard, {@link #exceptionally} observes the failure with the completion
 * wrappers unwrapped, and {@link #orDisablePlugin} turns a failure into a clean plugin
 * disable (the bootstrap gate). {@link #join()} blocks the calling thread and is meant
 * for the shutdown flush and the enable-time bootstrap only: any other main-thread join
 * logs one WARN with the calling frames. {@link #joinWithin(Duration)} is the same wait
 * under a budget, for the shutdown flush that must not outlive the stop.</p>
 *
 * <p>Why the timed variant exists: Bukkit clears the plugin's enabled flag BEFORE
 * {@code onDisable} runs, so from the first line of {@code onInnerDisable} every
 * {@link #thenSync} continuation is dropped by its is-enabled guard. Blocking is
 * therefore the only way left to observe that a teardown write landed - and an
 * unbounded block on one unreachable database would hold the server stop open for as
 * long as the JDBC driver takes.</p>
 */
public final class SnFuture<T> {

    private static final int JOIN_WARN_FRAMES = 5;

    private final Sn ctx;
    private final @Nullable SnDb db;
    private final boolean mainThreadCompleted;
    final CompletableFuture<T> delegate;

    SnFuture(Sn ctx, @Nullable SnDb db, CompletableFuture<T> delegate) {
        this(ctx, db, delegate, false);
    }

    private SnFuture(Sn ctx, @Nullable SnDb db, CompletableFuture<T> delegate,
            boolean mainThreadCompleted) {
        this.ctx = ctx;
        this.db = db;
        this.delegate = delegate;
        this.mainThreadCompleted = mainThreadCompleted;
    }

    /**
     * Wraps an arbitrary {@code CompletableFuture} in the SnFuture consumption surface
     * ({@link #thenSync}, {@link #exceptionally}, {@link #join}) of the given context.
     * Used by library modules outside the db package (such as {@code SnPapi.applyOnMain})
     * and available to consumers. The join-on-bootstrap/shutdown allowance applies to DB
     * futures only.
     */
    public static <T> SnFuture<T> wrap(Sn ctx, CompletableFuture<T> future) {
        return new SnFuture<>(ctx, null, future);
    }

    /**
     * Like {@link #wrap} for a future that can ONLY complete on the main thread (bridge
     * responses, timeouts and teardowns all resolve there): {@link #join()} from the
     * main thread would deadlock the server forever, so it throws instead. Consume with
     * {@link #thenSync}/{@link #exceptionally}.
     */
    public static <T> SnFuture<T> wrapMainCompleted(Sn ctx, CompletableFuture<T> future) {
        return new SnFuture<>(ctx, null, future, true);
    }

    /**
     * Consumes the value on the main thread; the hop is skipped when the owning plugin
     * is already disabled, and a failed future logs one WARN instead of reaching the
     * consumer.
     */
    public SnFuture<T> thenSync(Consumer<T> consumer) {
        ctx.scheduler().thenSync(delegate, consumer);
        return this;
    }

    /** Observes a failure with {@code CompletionException} unwrapped to the real cause. */
    public SnFuture<T> exceptionally(Consumer<Throwable> handler) {
        delegate.whenComplete((value, error) -> {
            if (error != null) {
                handler.accept(unwrap(error));
            }
        });
        return this;
    }

    /**
     * Blocks until the value is available and returns it. Joining on the main thread
     * outside the shutdown or bootstrap phases logs one WARN with the first
     * {@value #JOIN_WARN_FRAMES} calling frames.
     *
     * <p>The wait itself is unbounded: use {@link #joinWithin(Duration)} when the caller
     * needs a budget it can survive, such as a teardown flush against a remote database.</p>
     */
    public T join() {
        failIfMainCompletedOnMain("join() on the main thread would never return");
        warnIfMainThreadJoin("join()");
        return delegate.join();
    }

    /**
     * Blocks until this future settles or {@code timeout} elapses, and reports which of
     * the two happened: {@code true} when it settled in time, {@code false} when the
     * budget ran out with the work still in flight. The bounded form of {@link #join()},
     * for the teardown flush that must not outlive the server stop.
     *
     * <p>Three outcomes, all distinguishable:</p>
     * <ul>
     *   <li><b>settled normally</b> - returns {@code true}. The value is then available
     *       from {@link #join()}, which returns immediately on a completed future.</li>
     *   <li><b>settled exceptionally</b> - throws, exactly like {@link #join()}: a
     *       {@link CompletionException} wrapping the cause, or a
     *       {@link java.util.concurrent.CancellationException} if it was cancelled. A
     *       thrown exception therefore means "the operation FAILED", never "it is still
     *       running".</li>
     *   <li><b>timed out</b> - returns {@code false} and leaves the work running: the
     *       future is neither cancelled nor completed, and a late success or failure
     *       still completes it normally afterwards. Use {@link #exceptionally} beforehand
     *       if a late failure must still be observed.</li>
     * </ul>
     *
     * <p>An interrupt while waiting re-arms the interrupt flag and returns {@code false},
     * treating it as "did not settle". A null timeout is rejected; a zero or negative one
     * polls once without blocking. The main-thread rules of {@link #join()} carry over
     * unchanged: waiting during teardown or bootstrap is silent, any other main-thread
     * wait logs the same one-off WARN with the calling frames, and a future that can only
     * complete ON the main thread still refuses to be waited on from it (a budget would
     * not break that deadlock, only postpone it).</p>
     *
     * @param timeout how long to wait at most; never null
     * @return true if the future settled within the budget, false if it did not
     */
    public boolean joinWithin(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        failIfMainCompletedOnMain("joinWithin() on the main thread could only run out its budget");
        warnIfMainThreadJoin("joinWithin()");
        long nanos;
        try {
            nanos = Math.max(0L, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        try {
            delegate.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException wrapped) {
                throw wrapped;
            }
            throw new CompletionException(cause == null ? e : cause);
        }
    }

    /**
     * Disables the owning plugin when this future fails; the standard gate for
     * {@link SnDb#bootstrap}.
     */
    public SnFuture<T> orDisablePlugin() {
        delegate.whenComplete((value, error) -> {
            if (error == null) {
                return;
            }
            JavaPlugin plugin = ctx.plugin();
            plugin.getLogger().severe("Critical database operation failed; disabling "
                    + plugin.getName() + ": " + unwrap(error));
            if (Bukkit.isPrimaryThread()) {
                Bukkit.getPluginManager().disablePlugin(plugin);
                return;
            }
            try {
                ctx.scheduler().sync(() -> Bukkit.getPluginManager().disablePlugin(plugin));
            } catch (IllegalPluginAccessException e) {
                plugin.getLogger().warning(
                        "Deferred disable discarded: plugin already disabled during scheduling");
            }
        });
        return this;
    }

    private void failIfMainCompletedOnMain(String detail) {
        if (mainThreadCompleted && !delegate.isDone() && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("This future completes ON the main thread (bridge"
                    + " frames/sweep): " + detail + "; use thenSync/exceptionally");
        }
    }

    private void warnIfMainThreadJoin(String method) {
        if (delegate.isDone() || !Bukkit.isPrimaryThread()
                || ctx.isShuttingDown() || (db != null && db.inBootstrap())) {
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder message = new StringBuilder(
                "SnFuture." + method + " on the main thread outside shutdown/bootstrap:");
        int shown = 0;
        for (int i = 3; i < stack.length && shown < JOIN_WARN_FRAMES; i++, shown++) {
            message.append("\n  at ").append(stack[i]);
        }
        ctx.plugin().getLogger().warning(message.toString());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
