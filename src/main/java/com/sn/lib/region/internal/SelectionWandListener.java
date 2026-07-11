package com.sn.lib.region.internal;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.sn.lib.region.SelectionManager;

/**
 * Single shared listener owned by SnLib that captures the block clicks of selection
 * wands. Inscribed in the ListenerHub, which performs the single event registration of
 * the whole library from the SnLibPlugin bootstrap; this class never registers itself.
 *
 * <p>Hot-path contract (this listener sees every interact event of the server): layered
 * quick-exits in a fixed order: action (only LEFT_CLICK_BLOCK / RIGHT_CLICK_BLOCK; air
 * clicks and PHYSICAL are ignored, no raytrace), main hand only (drops the offhand echo
 * of the dual fire), null/air/meta-less item, then the PDC scan for the owner-namespaced
 * {@code snlib_selection_wand} tag resolving the owning manager.</p>
 *
 * <p>Deliberate decision inherited from the SnGens admin wand: the event is cancelled
 * BEFORE the permission gate, so a wand in hand never breaks or uses blocks even without
 * permission; and LOWEST priority with {@code ignoreCancelled = false} makes the wand win
 * over terrain protections (it is an administrative tool). Left click sets pos1, right
 * click sets pos2; the message and completion pipeline runs inside the manager.</p>
 */
public final class SelectionWandListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        SelectionManager manager = null;
        String specId = null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (!SelectionManager.WAND_TAG.equals(key.getKey())) {
                continue;
            }
            String value = pdc.get(key, PersistentDataType.STRING);
            if (value == null) {
                continue;
            }
            SelectionManager resolved = SelectionManager.forNamespace(key.getNamespace());
            if (resolved != null) {
                manager = resolved;
                specId = value;
                break;
            }
        }
        if (manager == null) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        manager.handleWandClick(event.getPlayer(), specId,
                action == Action.LEFT_CLICK_BLOCK, event.getClickedBlock().getLocation());
    }
}
