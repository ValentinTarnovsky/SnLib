package com.sn.lib.region.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.region.Cuboid;
import com.sn.lib.region.SelectionManager;
import com.sn.lib.region.SelectionSession;
import com.sn.lib.region.SelectionSpec;
import com.sn.lib.util.LocationUtil;

/**
 * Repeating particle renderer of ONE selection session: draws the live edges of the
 * selected box (or a one-block marker while only one position is set) and owns the
 * session timeout. One instance per session with an active renderer, armed by
 * {@code SelectionManager.refreshRenderer} through the context scheduler.
 *
 * <p>Folia justification (same documented rationale as the SnGens admin wand): the task
 * runs on the main thread / global region via SnScheduler, and per-player
 * {@code spawnParticle} only sends packets to that viewer; the renderer NEVER touches
 * world logic (no blocks, no entities, no chunk loads) and uses zero NMS.</p>
 *
 * <p>Particle resolution is lenient (open-set enum policy of the library) and cached
 * once per instance: {@code Particle.valueOf} on the uppercased spec name with the
 * bidirectional REDSTONE&lt;-&gt;DUST alias; an unresolvable name falls back to FLAME
 * with one WARN per name, and any particle other than DUST whose required data type is
 * not Void degrades to FLAME with one WARN (the rich data grammar belongs to the
 * {@code [particle]} action, not here). Only DUST receives data on emission.</p>
 */
public final class SelectionRenderer implements Runnable {

    /** Server-wide static justified: WARN dedup keyed by offending particle name. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final SelectionManager manager;
    private final SelectionSession session;
    private final Particle particle;
    /** Cached dust data, resolved once per instance; null for every non-DUST particle. */
    private final Particle.DustOptions dustOptions;

    /** Resolves and caches the particle and its DUST options once per instance. */
    public SelectionRenderer(SelectionManager manager, SelectionSession session) {
        this.manager = manager;
        this.session = session;
        SelectionSpec spec = session.spec();
        Particle resolved = resolveParticle(spec.particleName());
        if (resolved.getDataType() == Particle.DustOptions.class) {
            this.particle = resolved;
            this.dustOptions = new Particle.DustOptions(
                    Color.fromRGB(spec.dustRed(), spec.dustGreen(), spec.dustBlue()),
                    spec.dustSize());
        } else {
            if (resolved.getDataType() != Void.class) {
                warnOnce("data:" + resolved.name(), "Selection particle '"
                        + resolved.name() + "' requires data; using FLAME");
                resolved = Particle.FLAME;
            }
            this.particle = resolved;
            this.dustOptions = null;
        }
    }

    @Override
    public void run() {
        Player owner = Bukkit.getPlayer(session.playerId());
        if (owner == null || !owner.isOnline()) {
            manager.handleRendererOffline(session.playerId());
            return;
        }
        SelectionSpec spec = session.spec();
        if (spec.timeoutTicks() > 0 && System.currentTimeMillis() - session.createdAtMillis()
                >= spec.timeoutTicks() * 50L) {
            manager.handleRendererTimeout(session.playerId());
            return;
        }
        Cuboid box = renderBox(owner);
        if (box == null) {
            return;
        }
        World world = Bukkit.getWorld(box.worldName());
        if (world == null) {
            return;
        }
        double minX = box.minX();
        double minY = box.minY();
        double minZ = box.minZ();
        double maxX = box.maxX() + 1.0D;
        double maxY = box.maxY() + 1.0D;
        double maxZ = box.maxZ() + 1.0D;
        boolean cornersOnly = box.size() > spec.maxRenderVolume();
        double step = spec.step();
        if (!cornersOnly) {
            double span = (maxX - minX) + (maxY - minY) + (maxZ - minZ);
            double points = Math.ceil(4.0D * span / step);
            if (points > spec.particleBudget()) {
                // Sparser but WHOLE box: the effective step stretches so the budget
                // covers every edge instead of cutting an edge halfway. The floor keeps
                // the loop finite even if a future spec path skips the build() clamps.
                step = Math.max(0.1D, 4.0D * span / spec.particleBudget());
            }
        }
        List<Player> viewers = viewers(owner, world, box, spec);
        if (viewers.isEmpty()) {
            return;
        }
        if (cornersOnly) {
            drawCorners(viewers, minX, minY, minZ, maxX, maxY, maxZ, spec.step());
        } else {
            drawEdges(viewers, minX, minY, minZ, maxX, maxY, maxZ, step);
        }
    }

    /**
     * Box to draw this tick: the full cuboid when both positions share a world; the
     * one-block marker {@code [b, b+1)} of a single set position (first-click feedback);
     * with positions in DIFFERENT worlds, the marker of the one in the OWNER's world.
     * Null (nothing drawn this tick, task keeps running) when no position qualifies.
     */
    private @Nullable Cuboid renderBox(Player owner) {
        Cuboid cuboid = session.cuboid();
        if (cuboid != null) {
            return cuboid;
        }
        Location pos1 = session.pos1();
        Location pos2 = session.pos2();
        boolean has1 = pos1 != null && pos1.isWorldLoaded();
        boolean has2 = pos2 != null && pos2.isWorldLoaded();
        Location marker;
        if (has1 && has2) {
            String ownerWorld = owner.getWorld().getName();
            marker = pos1.getWorld().getName().equals(ownerWorld) ? pos1
                    : pos2.getWorld().getName().equals(ownerWorld) ? pos2 : null;
        } else {
            marker = has1 ? pos1 : has2 ? pos2 : null;
        }
        if (marker == null) {
            return null;
        }
        return Cuboid.of(marker.getWorld().getName(),
                marker.getBlockX(), marker.getBlockY(), marker.getBlockZ(),
                marker.getBlockX(), marker.getBlockY(), marker.getBlockZ());
    }

    /**
     * Viewers passing the distance culling against the CLOSEST point of the box
     * (per-axis clamp via {@link LocationUtil#distanceToBoxSquared}, correct on huge
     * boxes where the center sits far away): OWNER_ONLY yields at most the owner; WORLD
     * yields every player of the box's world in range. The particle budget applies PER
     * viewer, so the emission cost scales viewers x points.
     */
    private List<Player> viewers(Player owner, World world, Cuboid box, SelectionSpec spec) {
        double maxSq = spec.renderDistance() * spec.renderDistance();
        if (spec.visibility() == SelectionSpec.Visibility.OWNER_ONLY) {
            if (LocationUtil.distanceToBoxSquared(box, owner.getLocation()) <= maxSq) {
                return List.of(owner);
            }
            return List.of();
        }
        List<Player> out = new ArrayList<>();
        for (Player viewer : world.getPlayers()) {
            if (LocationUtil.distanceToBoxSquared(box, viewer.getLocation()) <= maxSq) {
                out.add(viewer);
            }
        }
        return out;
    }

    /** Draws the 12 edges of {@code [min, max)} placing one point every {@code step} blocks. */
    private void drawEdges(List<Player> viewers, double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ, double step) {
        for (double x = minX; x <= maxX; x += step) {
            emit(viewers, x, minY, minZ);
            emit(viewers, x, minY, maxZ);
            emit(viewers, x, maxY, minZ);
            emit(viewers, x, maxY, maxZ);
        }
        for (double y = minY; y <= maxY; y += step) {
            emit(viewers, minX, y, minZ);
            emit(viewers, minX, y, maxZ);
            emit(viewers, maxX, y, minZ);
            emit(viewers, maxX, y, maxZ);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            emit(viewers, minX, minY, z);
            emit(viewers, minX, maxY, z);
            emit(viewers, maxX, minY, z);
            emit(viewers, maxX, maxY, z);
        }
    }

    /**
     * Corners-only mode for boxes above max-render-volume: each of the 8 corners is
     * marked with a mini cross of 3 short segments (1 block along each axis, pointing
     * into the box, spec step), more visible than a single point and always cheap
     * (under 200 points at the default step).
     */
    private void drawCorners(List<Player> viewers, double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ, double step) {
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    double x = cx == 0 ? minX : maxX;
                    double y = cy == 0 ? minY : maxY;
                    double z = cz == 0 ? minZ : maxZ;
                    double dx = cx == 0 ? 1.0D : -1.0D;
                    double dy = cy == 0 ? 1.0D : -1.0D;
                    double dz = cz == 0 ? 1.0D : -1.0D;
                    for (double d = 0.0D; d <= 1.0D; d += step) {
                        emit(viewers, x + dx * d, y, z);
                        emit(viewers, x, y + dy * d, z);
                        emit(viewers, x, y, z + dz * d);
                    }
                }
            }
        }
    }

    /** Per-player packet-only emission; only DUST carries data. */
    private void emit(List<Player> viewers, double x, double y, double z) {
        for (Player viewer : viewers) {
            if (dustOptions != null) {
                viewer.spawnParticle(particle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, dustOptions);
            } else {
                viewer.spawnParticle(particle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    /**
     * Lenient by-name resolution: {@code valueOf} on the uppercased name, bidirectional
     * REDSTONE&lt;-&gt;DUST alias, FLAME fallback with one WARN per unresolvable name.
     */
    private static Particle resolveParticle(String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        Particle direct = lookup(name);
        if (direct != null) {
            return direct;
        }
        String alias = "REDSTONE".equals(name) ? "DUST"
                : "DUST".equals(name) ? "REDSTONE" : null;
        if (alias != null) {
            Particle aliased = lookup(alias);
            if (aliased != null) {
                warnOnce("alias:" + name, "Selection particle '" + name
                        + "' does not exist on this server; using alias '" + alias + "'");
                return aliased;
            }
        }
        warnOnce("name:" + name, "Invalid selection particle '" + raw + "'; using FLAME");
        return Particle.FLAME;
    }

    private static @Nullable Particle lookup(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException notAConstant) {
            return null;
        }
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
