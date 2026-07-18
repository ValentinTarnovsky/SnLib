package com.sn.lib.teleport.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import com.sn.lib.teleport.Teleports;

/**
 * Single shared listener owned by SnLib that cancels a pending warmup teleport when the
 * player takes damage. Inscribed in the ListenerHub, which performs the single event
 * registration of the whole library from the SnLibPlugin bootstrap; this class never
 * registers itself.
 *
 * <p>Observes at MONITOR with {@code ignoreCancelled = true}: it reads the final,
 * uncancelled damage without altering it, and cancels the teleport of the damaged player.
 * Non-player entities quick-exit first, then the dispatch acts solely for contexts that
 * declared the teleport module (no declared module, no manager, nothing runs).</p>
 */
public final class TeleportDamageListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            Teleports.dispatchDamage(player);
        }
    }
}
