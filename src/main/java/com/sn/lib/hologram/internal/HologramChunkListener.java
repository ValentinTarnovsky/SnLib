package com.sn.lib.hologram.internal;

import java.util.Collection;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

import com.sn.lib.hologram.HologramUtil;

/**
 * Shared orphan purge for hologram entities, owned by SnLib. Inscribed in the ListenerHub;
 * the registerEvents call happens UNIQUELY in the SnLibPlugin bootstrap.
 *
 * <p>Listens to {@link EntitiesLoadEvent}, the chunk-load signal that actually carries the
 * chunk's entities (ChunkLoadEvent fires before they attach): every marked
 * {@link TextDisplay} that no live hologram registration claims is removed, and claimed
 * ones re-bind their fresh entity instance through {@link HologramUtil#adopt}.
 * {@link #purgeLoadedWorlds()} runs the same pass over every loaded world, deferred to the
 * first tick after the bootstrap enables so worlds are loaded and every consumer had its
 * chance to register.</p>
 */
public final class HologramChunkListener implements Listener {

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        purge(event.getEntities());
    }

    /** Purges hologram orphans among the given entities; returns how many were removed. */
    public static int purge(Collection<Entity> entities) {
        int purged = 0;
        for (Entity entity : entities) {
            if (entity instanceof TextDisplay display && !HologramUtil.adopt(display)) {
                display.remove();
                purged++;
            }
        }
        return purged;
    }

    /** Scans every loaded world for hologram orphans; returns how many were removed. */
    public static int purgeLoadedWorlds() {
        int purged = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (!HologramUtil.adopt(display)) {
                    display.remove();
                    purged++;
                }
            }
        }
        return purged;
    }
}
