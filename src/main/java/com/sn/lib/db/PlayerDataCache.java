package com.sn.lib.db;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Per-player data cache bound to one {@link SnDb}, created via {@link SnDb#playerCache}.
 *
 * <p>Lifecycle: the shared PlayerJoinEvent listener triggers {@link #load} for every
 * registered cache of every owner, and the quit cleanup listener saves the entry when
 * dirty and removes it. The loader runs on the owning plugin's async pool (never on the
 * database executor, so it may join queries of this module) and its result is installed
 * on the main thread; the saver runs on the caller thread and is expected to enqueue
 * asynchronous writes such as {@link SnDb#upsert}.</p>
 *
 * <p>Concurrent loads of the same player dedup into one in-flight attempt, and a
 * mutation-sequence guard discards stale results: each load takes a fresh ticket and
 * installs only while its own ticket is still mapped, so an invalidate or a quit during
 * the load can never be overwritten by data that was already in flight.</p>
 *
 * <p>Ordered teardown: {@link #saveAll()} plus a join of its barrier future runs before
 * {@link SnDb#shutdown()} (wired through {@code SnDb.flushPlayerCaches()}), so no dirty
 * entry is lost to the pool close.</p>
 */
public final class PlayerDataCache<T> {

    /** Persists one player's value; typically an {@link SnDb#upsert} enqueued off-main. */
    public interface Saver<T> {
        void save(SnDb db, UUID uuid, T value);
    }

    /**
     * Server-wide static justified: player caches keyed per owning plugin, resolved by
     * the shared join listener and swept whole-key on owner disable.
     */
    private static final TenantRegistry<PlayerDataCache<?>> CACHES = new TenantRegistry<>();

    private final Sn ctx;
    private final SnDb db;
    private final BiFunction<SnDb, UUID, T> loader;
    private final Saver<T> saver;
    private final ConcurrentHashMap<UUID, T> data = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    /**
     * Mutation-sequence guard: maps each player to the ticket of its in-flight load. A
     * mapped ticket dedups concurrent loads, and any mutation (invalidate, quit unload)
     * drops it so the stale async result is discarded on arrival.
     */
    private final ConcurrentHashMap<UUID, Long> pendingLoads = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    PlayerDataCache(Sn ctx, SnDb db, BiFunction<SnDb, UUID, T> loader, Saver<T> saver) {
        this.ctx = ctx;
        this.db = db;
        this.loader = loader;
        this.saver = saver;
        CACHES.add(ctx.plugin(), this);
        QuitCleanupListener.register(ctx.plugin(), this::unload);
    }

    /**
     * Shared PlayerJoinEvent listener owned by SnLib that triggers load-on-join for every
     * registered cache of every owner. Defined here and inscribed in the ListenerHub; the
     * registerEvents call happens uniquely in the SnLibPlugin bootstrap.
     */
    public static Listener joinListener() {
        return new JoinListener();
    }

    /** Cached value; null while not loaded (or when the loader returned no data). */
    public @Nullable T get(UUID uuid) {
        return data.get(uuid);
    }

    /**
     * Loads the player's value asynchronously and installs it on the main thread. No-op
     * when the value is already cached or a load is already in flight (dedup).
     */
    public void load(UUID uuid) {
        if (ctx.isShuttingDown() || data.containsKey(uuid)) {
            return;
        }
        long ticket = sequence.incrementAndGet();
        if (pendingLoads.putIfAbsent(uuid, ticket) != null) {
            return;
        }
        CompletableFuture<T> future = ctx.scheduler().supplyAsync(() -> loader.apply(db, uuid));
        future.whenComplete((value, error) -> {
            if (error != null) {
                pendingLoads.remove(uuid, ticket);
            }
        });
        ctx.scheduler().thenSync(future, value -> {
            if (!pendingLoads.remove(uuid, ticket)) {
                return;
            }
            if (value != null) {
                data.put(uuid, value);
            }
        });
    }

    /** Marks the player's loaded value as pending persistence; no-op while not loaded. */
    public void markDirty(UUID uuid) {
        if (data.containsKey(uuid)) {
            dirty.add(uuid);
        }
    }

    /** Discards the player's entry WITHOUT saving, killing any in-flight load. */
    public void invalidate(UUID uuid) {
        pendingLoads.remove(uuid);
        data.remove(uuid);
        dirty.remove(uuid);
    }

    /**
     * Saves every dirty entry through the saver and returns a barrier future that
     * completes once the writes enqueued so far drained; the ordered teardown joins it
     * before {@link SnDb#shutdown()}.
     */
    public SnFuture<Void> saveAll() {
        for (UUID uuid : List.copyOf(dirty)) {
            T value = data.get(uuid);
            if (dirty.remove(uuid) && value != null) {
                save(uuid, value);
            }
        }
        return db.fence();
    }

    /**
     * Quit/kick cleanup: saves the value when dirty and removes the entry. Idempotent,
     * since a kicked player fires both kick and quit.
     */
    void unload(UUID uuid) {
        pendingLoads.remove(uuid);
        T value = data.remove(uuid);
        if (dirty.remove(uuid) && value != null) {
            save(uuid, value);
        }
    }

    private void save(UUID uuid, T value) {
        try {
            saver.save(db, uuid, value);
        } catch (Throwable t) {
            ctx.plugin().getLogger()
                    .warning("Save de datos de jugador fallo (" + uuid + "): " + t);
        }
    }

    private static final class JoinListener implements Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            CACHES.forEachOwner((owner, caches) -> {
                for (PlayerDataCache<?> cache : caches) {
                    cache.load(uuid);
                }
            });
        }
    }
}
