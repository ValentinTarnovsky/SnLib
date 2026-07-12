package com.sn.lib.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.text.SnText;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;

/**
 * Hologram service of a consumer context, reached through {@code sn.holograms()}.
 *
 * <p>Holograms are real {@link TextDisplay} entities (1.19.4+ API, zero NMS and zero
 * packets). Every entity carries the PDC marker {@code snlib:snlib_hologram} with value
 * {@code <plugin>:<id>}. Marked entities whose marker no live registration claims are
 * orphans (previous run, crash, or a delete that could not reach an unloaded chunk) and
 * are purged by the internal chunk listener and by the startup scan; entities of live
 * markers get their fresh instance re-bound after a chunk reload.</p>
 *
 * <p>Lines render through the SnText pipeline with PAPI resolved serverside (null viewer);
 * an optional per-hologram refresh task re-renders on an interval. Entries live in a
 * tenant registry keyed by the owning plugin, so a disable sweeps them even when the
 * owner never cleaned up; the context teardown calls {@link #deleteAll()}.</p>
 */
public final class HologramUtil {

    /** Server-wide static justified: hologram entries keyed per owning plugin for the sweep. */
    private static final TenantRegistry<HologramEntry> HOLOGRAMS =
            new TenantRegistry<>(HologramUtil::sweep);

    /** PDC marker present on every hologram entity; value {@code <plugin>:<id>}. */
    private static final NamespacedKey MARKER_KEY = new NamespacedKey("snlib", "snlib_hologram");

    private final Sn ctx;
    private final Map<String, HologramEntry> byId = new ConcurrentHashMap<>();
    private final Set<String> warnedIds = ConcurrentHashMap.newKeySet();

    public HologramUtil(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Spawns the hologram {@code id} at the location, replacing a previous hologram with
     * the same id. The entity is persistent and marked: a re-spawn on every enable is the
     * expected model, since the previous run's entity is purged as an orphan when its
     * chunk loads.
     */
    public void spawn(String id, Location location, List<String> lines) {
        World world = location == null ? null : location.getWorld();
        if (world == null) {
            ctx.plugin().getLogger().warning(
                    "Hologram '" + id + "': invalid location, spawn ignored");
            return;
        }
        delete(id);
        String marker = ctx.plugin().getName() + ":" + id;
        HologramEntry entry = new HologramEntry(marker, copyOf(lines));
        entry.lastText = render(entry.rawLines);
        TextDisplay display = world.spawn(location, TextDisplay.class);
        display.setPersistent(true);
        display.setBillboard(entry.billboard);
        display.getPersistentDataContainer().set(MARKER_KEY, PersistentDataType.STRING, marker);
        display.text(entry.lastText);
        entry.entity = display;
        byId.put(id, entry);
        HOLOGRAMS.add(ctx.plugin(), entry);
    }

    /** Replaces the hologram lines and re-renders immediately. */
    public void setLines(String id, List<String> lines) {
        HologramEntry entry = get(id);
        if (entry == null) {
            return;
        }
        entry.rawLines = copyOf(lines);
        apply(entry);
    }

    /** Billboard mode of the entity (default CENTER, the classic hologram behaviour). */
    public void setBillboard(String id, Display.Billboard billboard) {
        HologramEntry entry = get(id);
        if (entry == null || billboard == null) {
            return;
        }
        entry.billboard = billboard;
        TextDisplay display = entry.entity;
        if (display != null && display.isValid()) {
            display.setBillboard(billboard);
        }
    }

    /**
     * Re-renders the hologram every {@code intervalTicks} (PAPI tokens included); an
     * interval of 0 or less cancels the refresh. One task handle per hologram; a new
     * interval replaces the previous task.
     */
    public void refreshEvery(String id, long intervalTicks) {
        HologramEntry entry = get(id);
        if (entry == null) {
            return;
        }
        cancelRefresh(entry);
        if (intervalTicks <= 0L) {
            return;
        }
        entry.refreshTask = ctx.scheduler().timer(intervalTicks, intervalTicks, () -> apply(entry));
    }

    /** Makes the hologram visible again for the viewer (holograms are visible by default). */
    public void showTo(Player viewer, String id) {
        HologramEntry entry = get(id);
        TextDisplay display = entry == null ? null : entry.entity;
        if (viewer != null && display != null && display.isValid()) {
            viewer.showEntity(ctx.plugin(), display);
        }
    }

    /**
     * Hides the hologram from the viewer only; other players keep seeing it. Per-viewer
     * visibility is not persistent: it resets when the entity re-binds after a chunk
     * reload or a re-spawn.
     */
    public void hideFrom(Player viewer, String id) {
        HologramEntry entry = get(id);
        TextDisplay display = entry == null ? null : entry.entity;
        if (viewer != null && display != null && display.isValid()) {
            viewer.hideEntity(ctx.plugin(), display);
        }
    }

    /**
     * Deletes the hologram: its refresh task is cancelled and the entity removed. When
     * the entity sits in an unloaded chunk it cannot be touched here; its marker is no
     * longer live, so the orphan purge removes the persisted copy on the next chunk load.
     */
    public void delete(String id) {
        HologramEntry entry = byId.remove(id);
        if (entry == null) {
            return;
        }
        HOLOGRAMS.remove(ctx.plugin(), entry);
        sweep(entry);
    }

    /** Deletes every hologram of this context; the context teardown calls this. */
    public void deleteAll() {
        for (String id : List.copyOf(byId.keySet())) {
            delete(id);
        }
    }

    /**
     * Chunk-load adoption contract used by the orphan purge (the internal chunk listener
     * and the startup scan). A display without the library marker is foreign and is left
     * alone. A marked display whose marker is claimed by a live registration re-binds as
     * that hologram's fresh entity instance (same UUID after a chunk reload, or a never
     * bound entry) and gets its current text and billboard re-applied.
     *
     * @return false when the display carries a library marker that no live hologram
     *         claims, or that is already bound to a different entity (a stale duplicate);
     *         the caller must remove such orphans
     */
    public static boolean adopt(TextDisplay display) {
        String marker = display.getPersistentDataContainer()
                .get(MARKER_KEY, PersistentDataType.STRING);
        if (marker == null) {
            return true;
        }
        HologramEntry live = findByMarker(marker);
        if (live == null) {
            return false;
        }
        TextDisplay tracked = live.entity;
        if (tracked == null || tracked.getUniqueId().equals(display.getUniqueId())) {
            live.entity = display;
            display.setBillboard(live.billboard);
            Component text = live.lastText;
            if (text != null) {
                display.text(text);
            }
            return true;
        }
        return false;
    }

    private static @Nullable HologramEntry findByMarker(String marker) {
        HologramEntry[] found = new HologramEntry[1];
        HOLOGRAMS.forEachOwner((owner, entries) -> {
            if (found[0] != null) {
                return;
            }
            for (HologramEntry entry : entries) {
                if (marker.equals(entry.marker)) {
                    found[0] = entry;
                    return;
                }
            }
        });
        return found[0];
    }

    private void apply(HologramEntry entry) {
        Component text = render(entry.rawLines);
        entry.lastText = text;
        TextDisplay display = entry.entity;
        if (display != null && display.isValid()) {
            display.text(text);
        }
    }

    private Component render(List<String> rawLines) {
        List<Component> rendered = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            rendered.add(SnText.color(SnText.normalizePapiOutput(ctx.papi().apply(null, line))));
        }
        return Component.join(JoinConfiguration.newlines(), rendered);
    }

    private @Nullable HologramEntry get(String id) {
        HologramEntry entry = byId.get(id);
        if (entry == null && warnedIds.add(id)) {
            ctx.plugin().getLogger().warning("Unknown hologram '" + id
                    + "': the operation is ignored (missing spawn(\"" + id + "\", ...))");
        }
        return entry;
    }

    /** Full release of one entry: refresh task cancelled, entity removed if reachable. */
    private static void sweep(HologramEntry entry) {
        cancelRefresh(entry);
        TextDisplay display = entry.entity;
        entry.entity = null;
        if (display != null) {
            try {
                display.remove();
            } catch (Throwable ignored) {
                // Entity already invalid or its chunk unloaded; the orphan purge covers it.
            }
        }
    }

    private static void cancelRefresh(HologramEntry entry) {
        TaskHandle task = entry.refreshTask;
        entry.refreshTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // Scheduler already gone during shutdown; nothing left to cancel.
            }
        }
    }

    private static List<String> copyOf(List<String> lines) {
        return lines == null ? List.of() : List.copyOf(lines);
    }

    private static final class HologramEntry {

        final String marker;
        volatile List<String> rawLines;
        volatile Display.Billboard billboard = Display.Billboard.CENTER;
        volatile @Nullable TextDisplay entity;
        volatile @Nullable Component lastText;
        volatile @Nullable TaskHandle refreshTask;

        HologramEntry(String marker, List<String> rawLines) {
            this.marker = marker;
            this.rawLines = rawLines;
        }
    }
}
