package com.sn.lib.papi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.db.SnFuture;
import com.sn.lib.debug.SnDebug;
import com.sn.lib.papi.internal.PapiHolder;

/**
 * PlaceholderAPI service of a consumer context, reached through {@code sn.papi()}.
 *
 * <p>{@link #apply(Player, String)} resolves PAPI tokens against the viewer, or against
 * the server when the viewer is null. With PlaceholderAPI absent the text is returned
 * intact and no PAPI class is ever loaded (isolation lives in the internal holder). PAPI
 * resolution is main-thread only: off the primary thread tokens are left intact and the
 * skip is recorded through the context debug service.</p>
 */
public final class SnPapi {

    private final Sn ctx;
    private final PapiHolder holder;

    /** Creates the service for the given context; PAPI presence is probed lazily. */
    public SnPapi(Sn ctx) {
        this.ctx = ctx;
        this.holder = new PapiHolder(ctx.plugin());
    }

    /**
     * Resolves PAPI tokens in {@code text}; a null viewer resolves against the server.
     * With PlaceholderAPI absent the text comes back intact. Off the primary thread the
     * tokens are left intact and the skip is logged through the debug service.
     */
    public String apply(@Nullable Player viewer, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        if (!holder.available()) {
            return text;
        }
        if (!Bukkit.isPrimaryThread()) {
            SnDebug debug = ctx.debug();
            if (debug != null) {
                debug.log(() -> "PAPI skipped off the main thread; tokens untouched: " + text);
            }
            return text;
        }
        return holder.apply(viewer, text);
    }

    /** List overload of {@link #apply(Player, String)}, resolving line by line. */
    public List<String> apply(@Nullable Player viewer, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(apply(viewer, line));
        }
        return out;
    }

    /**
     * Async-safe bridge to {@link #apply(Player, String)}. On the primary thread the
     * text resolves inline and the returned future is already completed; off it, the
     * resolution hops to the main thread through the context scheduler. Fail-open: a
     * resolver error or a scheduling failure (plugin disabled before the hop) completes
     * the future with the ORIGINAL unresolved text; null text completes with null.
     * Canonical consumption is {@code thenSync(...)}, as with the db futures.
     */
    public SnFuture<String> applyOnMain(@Nullable Player viewer, String text) {
        if (Bukkit.isPrimaryThread()) {
            return SnFuture.wrap(ctx, CompletableFuture.completedFuture(apply(viewer, text)));
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            ctx.scheduler().sync(() -> {
                try {
                    future.complete(apply(viewer, text));
                } catch (Throwable t) {
                    SnDebug debug = ctx.debug();
                    if (debug != null) {
                        debug.log(() -> "applyOnMain failed to resolve; original text untouched: " + t);
                    }
                    future.complete(text);
                }
            });
        } catch (IllegalPluginAccessException e) {
            future.complete(text);
        }
        return SnFuture.wrap(ctx, future);
    }

    /**
     * List overload of {@link #applyOnMain(Player, String)}: resolves the whole list in
     * ONE main-thread hop. Fail-open to the original list; null lines complete with null.
     */
    public SnFuture<List<String>> applyOnMain(@Nullable Player viewer, List<String> lines) {
        if (Bukkit.isPrimaryThread()) {
            return SnFuture.wrap(ctx, CompletableFuture.completedFuture(apply(viewer, lines)));
        }
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        try {
            ctx.scheduler().sync(() -> {
                try {
                    future.complete(apply(viewer, lines));
                } catch (Throwable t) {
                    SnDebug debug = ctx.debug();
                    if (debug != null) {
                        debug.log(() -> "applyOnMain failed to resolve the list; original lines untouched: " + t);
                    }
                    future.complete(lines);
                }
            });
        } catch (IllegalPluginAccessException e) {
            future.complete(lines);
        }
        return SnFuture.wrap(ctx, future);
    }

    /** True when the PlaceholderAPI plugin is present and enabled. */
    public boolean available() {
        return holder.available();
    }

    /** Drops the cached presence probe; the next apply or register probes again. */
    public void invalidate() {
        holder.invalidate();
    }

    /** Starts a declarative expansion under {@code identifier}; see {@link ExpansionBuilder}. */
    public ExpansionBuilder expansion(String identifier) {
        List<String> authors = ctx.plugin().getDescription().getAuthors();
        String author = authors.isEmpty() ? ctx.plugin().getName() : String.join(", ", authors);
        return new ExpansionBuilder(this, identifier, author,
                ctx.plugin().getDescription().getVersion());
    }

    /** Unregisters every expansion this context registered; invoked by the context teardown. */
    public void unregisterAll() {
        holder.unregisterAll();
    }

    boolean registerExpansion(String identifier, String author, String version,
            Map<String, Function<OfflinePlayer, String>> exact,
            Map<String, BiFunction<OfflinePlayer, String, String>> prefixed) {
        return holder.register(identifier, author, version, exact, prefixed);
    }
}
