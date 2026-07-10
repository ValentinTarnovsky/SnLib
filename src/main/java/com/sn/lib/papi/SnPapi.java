package com.sn.lib.papi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
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
                debug.log(() -> "PAPI omitido fuera del main thread; tokens intactos: " + text);
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
