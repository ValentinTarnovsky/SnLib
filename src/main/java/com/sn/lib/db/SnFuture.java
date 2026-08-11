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
 * is-enabled guard, {@link #chainSync} does the same but hands back a NEW future that
 * settles after the consumer ran (the form for a method that RETURNS its future, so the
 * caller's continuation is a successor and not a sibling), {@link #exceptionally} observes
 * the failure with the completion wrappers unwrapped, and {@link #orDisablePlugin} turns a
 * failure into a clean plugin disable (the bootstrap gate). Only {@link #chainSync} returns
 * a different future; the other three return {@code this} and therefore end a chain.
 * {@link #join()} blocks the calling thread and is meant
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
     * Used by library modules outside the db package and available to consumers. The
     * join-on-bootstrap/shutdown allowance applies to DB futures only. Use
     * {@link #wrapMainCompleted} instead whenever only a main-thread task can complete the
     * future, which is what {@code SnPapi.applyOnMain} returns from its off-main branch.
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
     *
     * <p>Returns THIS future, not a new one: a continuation registered on the returned
     * future is a SIBLING of this consumer rather than a successor, and sibling order is
     * unspecified. If this future is RETURNED to a caller who may chain onto it, use
     * {@link #chainSync} instead.</p>
     */
    public SnFuture<T> thenSync(Consumer<T> consumer) {
        ctx.scheduler().thenSync(delegate, consumer);
        return this;
    }

    /**
     * Consumes the value on the main thread like {@link #thenSync} and returns a NEW future
     * that settles only after that consumer has run, or after the hop was skipped because the
     * owning plugin is already gone. The form to use in a method that RETURNS the future it
     * just attached a consumer to.
     *
     * <p>Why it exists: {@link #thenSync}, {@link #exceptionally} and {@link #orDisablePlugin}
     * each register one more dependent on the SAME underlying {@code CompletableFuture} and
     * return {@code this}, so a method ending in {@code return write(...).thenSync(publish);}
     * and a caller writing {@code callee().thenSync(next)} do not build a chain, they build two
     * SIBLINGS of one completion. Sibling order is unspecified and OpenJDK pops dependents
     * last-registered-first, so {@code next} is scheduled BEFORE {@code publish} and reads
     * exactly the state the publish was about to install. Hopping to the main thread does not
     * rescue it: both tasks are then queued in that same inverted order. This is the only
     * method that hands back a DIFFERENT future, and therefore the only way to say "after the
     * publish". The fix belongs in the producer: swap its one {@code thenSync} for this and
     * every existing caller becomes correct without being touched.</p>
     *
     * <p>Outcomes, and which of them settle the returned future:</p>
     * <ul>
     *   <li><b>consumer ran</b> - completes normally with the SAME value, from inside the
     *       main-thread task, after the consumer returned. Whatever the caller registers on the
     *       returned future is registered on a future that was still pending until then, so it
     *       is a successor and not a sibling; that, and not any tick separation, is the whole
     *       ordering guarantee (a zero-delay task scheduled from inside a tick can and often
     *       does run in that same tick).</li>
     *   <li><b>this future failed</b> - the consumer does NOT run and the returned future fails
     *       with the same throwable, unchanged. Unlike {@link #thenSync}, which swallows a
     *       failure into one WARN, this hands it downstream, so terminate the chain with
     *       {@link #exceptionally} or {@link #orDisablePlugin}: a chained failure nobody
     *       consumes is a failure nobody sees.</li>
     *   <li><b>the consumer threw</b> - the returned future fails with that throwable and the
     *       rest of the chain does not run on a step that did not finish. Plain
     *       {@link #thenSync} lets the throw escape into Bukkit's task reporter and leaves the
     *       future untouched; that does NOT carry over, because a link that never settles is a
     *       permanent hang for whoever waits above it. Keep publish consumers total: a handler
     *       cannot tell this apart from the failure above.</li>
     *   <li><b>the hop was skipped</b> - the owning plugin is already disabled (Bukkit clears
     *       that flag BEFORE {@code onDisable}, so this covers everything from the first line of
     *       {@code onInnerDisable} onward), the context is already tearing down, or the
     *       scheduler refused the task in the disable race. The consumer never runs and the
     *       returned future completes NORMALLY with the value anyway: a teardown must never
     *       become a hang, and a downstream {@link #thenSync} is dropped by the same is-enabled
     *       guard regardless. A normal completion therefore does NOT prove the consumer ran, and
     *       the two cases are deliberately indistinguishable.</li>
     *   <li><b>the scheduler failed for any other reason</b> - not a disable, so it is not
     *       reported as a clean skip: the returned future fails with that throwable.</li>
     * </ul>
     *
     * <p><b>One window does not settle</b>, and it is the same window in which
     * {@link #thenSync} silently drops its consumer: the hop passed the is-enabled check, was
     * queued, and the plugin was disabled before the task ran, so Bukkit cancelled it. The
     * returned future then stays pending for the rest of the run. Nothing downstream executes,
     * which matches what {@code thenSync} would have done, but a wait on it never returns, which
     * is one more reason for the rule below.</p>
     *
     * <p><b>Never wait on the returned future.</b> While the plugin is running it can only
     * complete normally from inside a main-thread task, so it carries the
     * {@link #wrapMainCompleted} marking: {@link #join()} and {@link #joinWithin(Duration)}
     * throw {@link IllegalStateException} when called from the main thread while it is still
     * pending, instead of deadlocking the server. That guard is a published rule, not an
     * observed behaviour: it only fires while the future is pending, so a chain that already
     * settled joins fine and one that has not throws. Waiting from an async thread does not
     * throw, but it is only safe while the main thread is ticking; during a stop the main thread
     * is inside the teardown flush, so an async wait on a chained future is a deadlock the guard
     * cannot catch. A producer whose future is joined by a teardown flush must therefore NOT use
     * this method: joins belong on the future the database module returned.</p>
     *
     * <p>The marking is NOT applied when the plugin is already disabled or the context is
     * already tearing down at the moment this is called, because the hop can never be allowed
     * from then on and the returned future will settle on the completing thread. Without that
     * exception the guard would throw during a teardown flush on a wait that would have
     * returned, and an {@link IllegalStateException} escaping {@code onInnerDisable} takes the
     * rest of that flush with it. One window remains and is not closed: a teardown that STARTS
     * after this method returned leaves a future marked main-completed that settles off it, so a
     * wait begun on the main thread in that window still throws. The rule above is what makes
     * that window irrelevant in practice.</p>
     *
     * <p>Two more consequences worth knowing. A handler attached to the returned future runs on
     * whichever thread settled it, the MAIN thread when the consumer threw and the completing
     * thread (a database executor thread, typically) when this future failed or the hop was
     * skipped, so write it for the stricter of the two. And this method is the only one here
     * that returns something other than {@code this}: {@link #thenSync}, {@link #exceptionally}
     * and {@link #orDisablePlugin} TERMINATE a chain, so
     * {@code a.chainSync(x).thenSync(y).chainSync(z)} reintroduces exactly the sibling inversion
     * described above, with {@code z} running before {@code y}. Only chain {@code chainSync}
     * onto {@code chainSync}.</p>
     *
     * <p>Ordering is promised against the main thread only, the global region on Folia, where
     * {@code Bukkit.isPrimaryThread()} is true on any tick thread and the deadlock guard is
     * therefore approximate. The consumer and the value stay reachable until the hop runs, one
     * tick longer than {@link #thenSync} keeps them.</p>
     *
     * @param consumer main-thread work that must finish before the returned future settles;
     *                 never null, and keep it total, since a throw fails the chain
     * @return a new future carrying this future's value, settled after {@code consumer} ran;
     *         never {@code this}
     */
    public SnFuture<T> chainSync(Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        CompletableFuture<T> chained = new CompletableFuture<>();
        delegate.whenComplete((value, error) -> {
            JavaPlugin plugin = ctx.plugin();
            // isShuttingDown() as well as isEnabled(): the teardown cancels the scheduler
            // before it flushes the database, so from the moment that window opens a queued
            // hop can only be killed unrun. Settling here instead keeps the chain finite.
            boolean hopAllowed = plugin.isEnabled() && !ctx.isShuttingDown();
            chainStep(chained, value, error, consumer, hopAllowed,
                    ctx.scheduler()::sync, plugin.getLogger()::warning);
        });
        // The marking says "only a main-thread task can complete this", which turns a
        // main-thread wait from a silent server deadlock into an immediate throw. It is
        // conditional for a reason: once the plugin is disabled or the context is tearing down,
        // the hop below can never be allowed, so the derived future settles on the completing
        // thread instead. Claiming otherwise would make the guard fire during a teardown flush
        // on a wait that would in fact have returned - and that throw escapes onInnerDisable,
        // taking the rest of the consumer's flush with it. Evaluated here rather than per
        // branch because the flag is fixed when the future is built; a teardown that STARTS
        // after this line still leaves the narrow window the javadoc records.
        boolean mayCompleteOnMain = ctx.plugin().isEnabled() && !ctx.isShuttingDown();
        // db is carried verbatim for consistency (it is inert while the main-completed flag is
        // set, since that guard pre-empts every main-thread path the join WARN could reach).
        return new SnFuture<>(ctx, db, chained, mayCompleteOnMain);
    }

    /**
     * Sequencing core of {@link #chainSync}, split out with the hop and the log injected so
     * every branch is unit-testable without a running server. Completes {@code chained} on
     * every path it can reach; the one path it cannot reach is a queued hop the server
     * cancels before it runs.
     */
    static <T> void chainStep(CompletableFuture<T> chained, T value, @Nullable Throwable error,
            Consumer<T> consumer, boolean hopAllowed, Consumer<Runnable> hop,
            Consumer<String> warn) {
        // 1. A failed step must not run the consumer, and the failure is this stage's result:
        //    the caller's handler hangs off the returned future now, not off the source, so
        //    swallowing it into a WARN the way SnScheduler.thenSync does would leave that
        //    handler with nothing to observe.
        if (error != null) {
            chained.completeExceptionally(error);
            return;
        }
        // 2. The hop cannot happen: the same skip thenSync performs, except that this must
        //    SETTLE rather than return, or every wait above it hangs for the rest of the run.
        if (!hopAllowed) {
            chained.complete(value);
            return;
        }
        try {
            hop.accept(() -> {
                try {
                    consumer.accept(value);
                    // 3. Completed INSIDE the main-thread task, after the consumer returned:
                    //    this one line is the entire method.
                    chained.complete(value);
                } catch (Throwable t) {
                    // 4. A throwing consumer would otherwise strand the chain for ever.
                    chained.completeExceptionally(t);
                }
            });
        } catch (IllegalPluginAccessException disabled) {
            // 5. The disable race: enabled one line above, gone by the time Bukkit looked.
            //    Same outcome as branch 2, and the same WARN thenSync prints for it.
            warn.accept("Chained hop to main discarded: plugin disabled during scheduling");
            chained.complete(value);
        } catch (Throwable e) {
            // 6. Anything else is not a disable and must not be reported as a clean skip.
            //    Folia's global region scheduler is not bound to the Bukkit exception type.
            chained.completeExceptionally(e);
        }
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
