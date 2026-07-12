package com.sn.lib.leaderboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.db.SnFuture;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Leaderboard cache of a consumer context, reached through {@code sn.leaderboards()}.
 *
 * <p>Each board pairs an id with an asynchronous query fired on a fixed interval: the
 * supplier runs on the main thread and must only DISPATCH the async work (an
 * {@link com.sn.lib.db.SnDb} query already does), and the fresh result is folded into an
 * immutable {@link Snapshot} swapped behind a volatile reference. Reads
 * ({@link #getTop}, {@link #positionOf}, {@link #valueOf}) are lock-free cache lookups,
 * safe for PlaceholderAPI resolvers under their cache-only contract. Boards live in a
 * tenant registry keyed by the owning plugin, so a disable sweeps their refresh tasks
 * even when the owner never unregistered.</p>
 *
 * <p>Optional PlaceholderAPI exposure via {@link #exposePlaceholders(String)}:
 * {@code %<identifier>_top_<id>_<n>_name%}, {@code %<identifier>_top_<id>_<n>_value%}
 * and {@code %<identifier>_pos_<id>%}.</p>
 */
public final class LeaderboardCache {

    /** Server-wide static justified: boards keyed per owning plugin for the sweep. */
    private static final TenantRegistry<Board> BOARDS =
            new TenantRegistry<>(LeaderboardCache::sweep);

    /** Refresh floor of one second: a leaderboard query is never a per-tick loop. */
    private static final long MIN_REFRESH_TICKS = 20L;

    private final Sn ctx;
    private final Map<String, Board> byId = new ConcurrentHashMap<>();

    public LeaderboardCache(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Registers the board and arms its periodic refresh (first run next tick), replacing
     * any previous board under the same id. The interval is clamped to a one second
     * minimum; until the first query completes every read sees an empty snapshot.
     */
    public void register(String id, Duration refreshInterval,
            Supplier<SnFuture<List<Entry>>> query) {
        long periodTicks = Math.max(MIN_REFRESH_TICKS,
                refreshInterval == null ? 0L : (refreshInterval.toMillis() + 49L) / 50L);
        Board board = new Board(id, query);
        Board previous = byId.put(id, board);
        if (previous != null) {
            BOARDS.remove(ctx.plugin(), previous);
            sweep(previous);
        }
        BOARDS.add(ctx.plugin(), board);
        try {
            board.handle = ctx.scheduler().timer(1L, periodTicks, () -> refresh(board));
        } catch (IllegalPluginAccessException e) {
            // Owner disabled while arming the refresh; the board stays empty.
        }
    }

    /** Cancels the periodic refresh and forgets the id; unknown ids no-op. */
    public void unregister(String id) {
        Board board = byId.remove(id);
        if (board != null) {
            BOARDS.remove(ctx.plugin(), board);
            sweep(board);
        }
    }

    /** Top {@code n} entries of the current snapshot, best first; unknown ids -> empty. */
    public List<Entry> getTop(String id, int n) {
        Board board = byId.get(id);
        return board == null ? List.of() : board.snapshot.top(n);
    }

    /** 1-based position of the player; 0 when not ranked or the id is unknown. */
    public int positionOf(String id, UUID uuid) {
        Board board = byId.get(id);
        return board == null ? 0 : board.snapshot.positionOf(uuid);
    }

    /** Cached value of the player; 0 when not ranked or the id is unknown. */
    public double valueOf(String id, UUID uuid) {
        Board board = byId.get(id);
        return board == null ? 0.0 : board.snapshot.valueOf(uuid);
    }

    /**
     * Registers a PlaceholderAPI expansion exposing every board of this cache:
     * {@code top_<id>_<n>_name}, {@code top_<id>_<n>_value} and {@code pos_<id>}.
     * Resolvers only read the in-memory snapshots. Returns false with a WARN when
     * PlaceholderAPI is absent or rejects the expansion.
     */
    public boolean exposePlaceholders(String identifier) {
        return ctx.papi().expansion(identifier)
                .prefixed("top_", (player, rest) -> resolveTop(rest))
                .prefixed("pos_", (player, rest) ->
                        String.valueOf(positionOf(rest, player.getUniqueId())))
                .register();
    }

    /** Arms one refresh: dispatches the query and swaps the snapshot on completion. */
    private void refresh(Board board) {
        if (board.cancelled || ctx.isShuttingDown()) {
            return;
        }
        SnFuture<List<Entry>> future;
        try {
            future = board.query.get();
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning(
                    "Leaderboard query '" + board.id + "' threw an error: " + t);
            return;
        }
        if (future == null) {
            ctx.plugin().getLogger().warning(
                    "Leaderboard query '" + board.id + "' returned null; refresh skipped");
            return;
        }
        future.thenSync(entries -> {
            if (!board.cancelled && entries != null) {
                board.snapshot = Snapshot.of(entries);
            }
        });
    }

    /** Resolves {@code <id>_<n>_(name|value)}; null leaves the token unresolved. */
    private @Nullable String resolveTop(String rest) {
        int kindSep = rest.lastIndexOf('_');
        int rankSep = kindSep < 0 ? -1 : rest.lastIndexOf('_', kindSep - 1);
        if (rankSep <= 0) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(rest.substring(rankSep + 1, kindSep));
        } catch (NumberFormatException e) {
            return null;
        }
        Board board = byId.get(rest.substring(0, rankSep));
        if (board == null || rank < 1) {
            return null;
        }
        List<Entry> top = board.snapshot.top(rank);
        if (top.size() < rank) {
            return "";
        }
        Entry entry = top.get(rank - 1);
        return switch (rest.substring(kindSep + 1)) {
            case "name" -> entry.name();
            case "value" -> formatValue(entry.value());
            default -> null;
        };
    }

    /** Integral values render without the trailing {@code .0}. */
    private static String formatValue(double value) {
        long asLong = (long) value;
        return value == asLong ? String.valueOf(asLong) : String.valueOf(value);
    }

    /** Full release of one board: marked cancelled and its refresh task cancelled. */
    private static void sweep(Board board) {
        board.cancelled = true;
        TaskHandle handle = board.handle;
        board.handle = null;
        if (handle != null) {
            try {
                handle.cancel();
            } catch (Throwable ignored) {
                // Scheduler already gone during shutdown; nothing left to cancel.
            }
        }
    }

    /** One ranked row: player uuid, display name and the ranked value. */
    public record Entry(UUID uuid, String name, double value) {

        public Entry {
            name = name == null ? "" : name;
        }
    }

    /**
     * Immutable ranking snapshot; pure logic, no Bukkit. Entries sort by value descending
     * with the name as tie-break (the sort is stable), positions are 1-based and 0 means
     * not ranked. Instances never mutate: the cache swaps whole snapshots behind a
     * volatile reference, so readers are lock-free.
     */
    public static final class Snapshot {

        private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());

        private final List<Entry> ordered;
        private final Map<UUID, Integer> positions;

        private Snapshot(List<Entry> ordered, Map<UUID, Integer> positions) {
            this.ordered = ordered;
            this.positions = positions;
        }

        /** Snapshot with no entries. */
        public static Snapshot empty() {
            return EMPTY;
        }

        /**
         * Builds the snapshot from unordered entries: null rows are skipped, the rest
         * sort by value descending with name ascending as tie-break, and a uuid appearing
         * twice keeps its best (first) position.
         */
        public static Snapshot of(List<Entry> entries) {
            if (entries == null || entries.isEmpty()) {
                return EMPTY;
            }
            List<Entry> sorted = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (entry != null) {
                    sorted.add(entry);
                }
            }
            if (sorted.isEmpty()) {
                return EMPTY;
            }
            sorted.sort(Comparator.comparingDouble(Entry::value).reversed()
                    .thenComparing(Entry::name));
            Map<UUID, Integer> positions = new HashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                UUID uuid = sorted.get(i).uuid();
                if (uuid != null) {
                    positions.putIfAbsent(uuid, i + 1);
                }
            }
            return new Snapshot(List.copyOf(sorted), Map.copyOf(positions));
        }

        /** First {@code n} entries, best first; the whole ranking when n exceeds it. */
        public List<Entry> top(int n) {
            if (n <= 0) {
                return List.of();
            }
            return n >= ordered.size() ? ordered : ordered.subList(0, n);
        }

        /** 1-based position of the uuid; 0 when not ranked. */
        public int positionOf(@Nullable UUID uuid) {
            return uuid == null ? 0 : positions.getOrDefault(uuid, 0);
        }

        /** Ranked value of the uuid; 0 when not ranked. */
        public double valueOf(@Nullable UUID uuid) {
            Integer position = uuid == null ? null : positions.get(uuid);
            return position == null ? 0.0 : ordered.get(position - 1).value();
        }

        /** Number of ranked entries. */
        public int size() {
            return ordered.size();
        }
    }

    private static final class Board {

        final String id;
        final Supplier<SnFuture<List<Entry>>> query;
        volatile Snapshot snapshot = Snapshot.empty();
        volatile boolean cancelled;
        volatile @Nullable TaskHandle handle;

        Board(String id, Supplier<SnFuture<List<Entry>>> query) {
            this.id = id;
            this.query = query;
        }
    }
}
