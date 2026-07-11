package com.sn.lib.db;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
 * logs one WARN with the calling frames.</p>
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
     */
    public T join() {
        if (mainThreadCompleted && !delegate.isDone() && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Este future completa EN el main thread (frames/"
                    + "sweep del bridge): join() en el main thread nunca retornaria; usar"
                    + " thenSync/exceptionally");
        }
        warnIfMainThreadJoin();
        return delegate.join();
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
            plugin.getLogger().severe("Operacion critica de base de datos fallo; deshabilitando "
                    + plugin.getName() + ": " + unwrap(error));
            if (Bukkit.isPrimaryThread()) {
                Bukkit.getPluginManager().disablePlugin(plugin);
                return;
            }
            try {
                ctx.scheduler().sync(() -> Bukkit.getPluginManager().disablePlugin(plugin));
            } catch (IllegalPluginAccessException e) {
                plugin.getLogger().warning(
                        "Disable diferido descartado: plugin ya deshabilitado durante el scheduling");
            }
        });
        return this;
    }

    private void warnIfMainThreadJoin() {
        if (delegate.isDone() || !Bukkit.isPrimaryThread()
                || ctx.isShuttingDown() || (db != null && db.inBootstrap())) {
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder message = new StringBuilder(
                "SnFuture.join() en el main thread fuera de shutdown/bootstrap:");
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
