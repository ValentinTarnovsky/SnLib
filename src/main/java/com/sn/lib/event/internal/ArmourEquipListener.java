package com.sn.lib.event.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.sn.lib.event.EquipMethod;
import com.sn.lib.event.SnArmourEquipEvent;
import com.sn.lib.util.ArmourUtil;

/**
 * Shared listener owned by SnLib that synthesizes {@link SnArmourEquipEvent}.
 *
 * <p>Primary source: {@link PlayerArmorChangeEvent}. That event is
 * {@code @ApiStatus.Obsolete} (~1.21.4) but NOT removed; present and functional across
 * the whole 1.20.4-1.21.8+ range (SnGens uses it in production). Its use here is
 * DELIBERATE; migrate to {@code io.papermc.paper.event.entity.EntityEquipmentChangedEvent}
 * ONLY when the floor/baseline rises (that class exists in neither 1.21.1 nor 1.20.4).</p>
 *
 * <p>The primary source does not expose the input vector: manual changes are reported as
 * {@link EquipMethod#PICK_DROP} ({@link EquipMethod#BROKE} when the piece spent its
 * durability), while {@link EquipMethod#DISPENSER} (BlockDispenseArmorEvent) and
 * {@link EquipMethod#DEATH} (PlayerDeathEvent) come from their dedicated sources, deduped
 * against the primary one. Inscribed in the ListenerHub; the registerEvents call happens
 * UNIQUELY in the SnLibPlugin bootstrap.</p>
 */
public final class ArmourEquipListener implements Listener {

    /** Dispense dedupe mark: slot a dispenser equipped and the tick it happened. */
    private record DispenseMark(EquipmentSlot slot, int tick) {
    }

    /** Transient dedupe state bounded by online players, not per-consumer data. */
    private final Map<UUID, DispenseMark> dispensed = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (player.isDead()) {
            return; // DEATH is synthesized from PlayerDeathEvent.
        }
        EquipmentSlot slot = slotOf(event);
        if (slot == null) {
            return;
        }
        if (consumeDispenseMark(player.getUniqueId(), slot)) {
            return; // Already reported as DISPENSER.
        }
        ItemStack oldPiece = normalize(event.getOldItem());
        ItemStack newPiece = normalize(event.getNewItem());
        if (oldPiece == null && newPiece == null) {
            return;
        }
        new SnArmourEquipEvent(player, classify(oldPiece, newPiece), slot, oldPiece, newPiece)
                .call();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player)) {
            return;
        }
        EquipmentSlot slot = matchType(event.getItem());
        if (slot == null) {
            return;
        }
        ItemStack oldPiece = normalize(player.getInventory().getItem(slot));
        SnArmourEquipEvent equip = new SnArmourEquipEvent(
                player, EquipMethod.DISPENSER, slot, oldPiece, event.getItem());
        if (!equip.call()) {
            event.setCancelled(true);
            return;
        }
        dispensed.put(player.getUniqueId(), new DispenseMark(slot, Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        Player player = event.getEntity();
        PlayerInventory inventory = player.getInventory();
        fireDeath(player, EquipmentSlot.HEAD, inventory.getHelmet());
        fireDeath(player, EquipmentSlot.CHEST, inventory.getChestplate());
        fireDeath(player, EquipmentSlot.LEGS, inventory.getLeggings());
        fireDeath(player, EquipmentSlot.FEET, inventory.getBoots());
    }

    private static void fireDeath(Player player, EquipmentSlot slot, @Nullable ItemStack piece) {
        ItemStack oldPiece = normalize(piece);
        if (oldPiece != null) {
            new SnArmourEquipEvent(player, EquipMethod.DEATH, slot, oldPiece, null).call();
        }
    }

    /**
     * Matches the armour slot by Material name suffix; the source of truth is
     * {@link ArmourUtil#slotOf}.
     */
    static @Nullable EquipmentSlot matchType(@Nullable ItemStack item) {
        return ArmourUtil.slotOf(item);
    }

    /** Name-based mapping keeps the source slot enum open: an unknown type never hard-fails. */
    private static @Nullable EquipmentSlot slotOf(PlayerArmorChangeEvent event) {
        return switch (event.getSlotType().name()) {
            case "HEAD" -> EquipmentSlot.HEAD;
            case "CHEST" -> EquipmentSlot.CHEST;
            case "LEGS" -> EquipmentSlot.LEGS;
            case "FEET" -> EquipmentSlot.FEET;
            default -> {
                EquipmentSlot matched = matchType(event.getNewItem());
                yield matched != null ? matched : matchType(event.getOldItem());
            }
        };
    }

    private static EquipMethod classify(@Nullable ItemStack oldPiece,
            @Nullable ItemStack newPiece) {
        if (newPiece == null && broke(oldPiece)) {
            return EquipMethod.BROKE;
        }
        return EquipMethod.PICK_DROP;
    }

    private static boolean broke(@Nullable ItemStack oldPiece) {
        if (oldPiece == null) {
            return false;
        }
        short max = oldPiece.getType().getMaxDurability();
        return max > 0 && oldPiece.getItemMeta() instanceof Damageable damageable
                && damageable.getDamage() >= max;
    }

    private boolean consumeDispenseMark(UUID uuid, EquipmentSlot slot) {
        DispenseMark mark = dispensed.get(uuid);
        if (mark == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() - mark.tick() > 1) {
            dispensed.remove(uuid, mark);
            return false;
        }
        if (mark.slot() != slot) {
            return false;
        }
        dispensed.remove(uuid, mark);
        return true;
    }

    private static @Nullable ItemStack normalize(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() ? null : item;
    }
}
