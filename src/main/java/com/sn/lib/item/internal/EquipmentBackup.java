package com.sn.lib.item.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.item.ItemSerializer;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.util.InvUtil;
import com.sn.lib.yml.SnYml;

/**
 * Per-context backup of the real items displaced by {@code ItemRegistry.apply}, with
 * restore GUARANTEED on quit (registered in the QuitCleanupListener) and on shutdown
 * ({@link #restoreAll}, invoked by the context teardown).
 *
 * <p>Persistence is write-through and default-on: every store writes the displaced item
 * to the context's {@code data/equipment-backup.yml} through {@link SnYml#save} (which
 * goes synchronous during teardown) and every take/restore erases it, so a crash without
 * onDisable never loses the real item. Persisted entries are reloaded at construction;
 * the only opt-out is not declaring the yml module, which degrades to in-memory backups
 * with one WARN on first use.</p>
 */
public final class EquipmentBackup {

    /** Server-wide static justified: backup instances keyed per owner for the teardown. */
    private static final TenantRegistry<EquipmentBackup> BACKUPS = new TenantRegistry<>();

    private static final String STORE_PATH = "data/equipment-backup.yml";
    private static final String ROOT = "backups";

    private final Sn ctx;
    private final @Nullable SnYml store;
    private final Map<UUID, Map<EquipmentSlot, ItemStack>> byPlayer = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedNoStore = new AtomicBoolean();

    /**
     * Creates the backup service of a context, reloads its persisted entries and
     * registers the quit-restore callback.
     */
    public EquipmentBackup(Sn ctx) {
        this.ctx = ctx;
        this.store = mountStore(ctx);
        loadPersisted();
        BACKUPS.add(ctx.plugin(), this);
        QuitCleanupListener.register(ctx.plugin(), this::restore);
    }

    /**
     * Backs up the item displaced from the slot, write-through. An empty slot stores
     * nothing so a persisted entry from a previous crash stays authoritative, and a
     * lib-applied locked piece is never backed up (it is not the player's real item).
     */
    public void store(Player player, EquipmentSlot slot, @Nullable ItemStack displaced) {
        if (displaced == null || displaced.getType().isAir()) {
            return;
        }
        ItemPropertyListener.Match match = ItemPropertyListener.match(displaced);
        if (match != null && match.def().locked()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        byPlayer.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>())
                .put(slot, displaced.clone());
        persist(uuid, slot, displaced);
    }

    /** Copy of the backed-up item of the slot without consuming it, or null. */
    public @Nullable ItemStack peek(UUID uuid, EquipmentSlot slot) {
        Map<EquipmentSlot, ItemStack> slots = byPlayer.get(uuid);
        ItemStack stored = slots == null ? null : slots.get(slot);
        return stored == null ? null : stored.clone();
    }

    /** Consumes the backed-up item of the slot, erasing its persisted entry, or null. */
    public @Nullable ItemStack take(UUID uuid, EquipmentSlot slot) {
        Map<EquipmentSlot, ItemStack> slots = byPlayer.get(uuid);
        if (slots == null) {
            return null;
        }
        ItemStack stored = slots.remove(slot);
        if (stored == null) {
            return null;
        }
        boolean emptied = slots.isEmpty();
        if (emptied) {
            byPlayer.remove(uuid, slots);
        }
        erase(uuid, slot, emptied);
        return stored;
    }

    /**
     * Restores every backed-up slot of the player: the applied locked piece of this
     * owner (or an empty slot) is replaced by the real item; any other occupant is
     * respected and the real item is given to the inventory instead. Offline players
     * are skipped so their persisted entries survive to the next session. Idempotent:
     * a kick fires both kick and quit and the second pass finds nothing.
     */
    public void restore(UUID uuid) {
        Map<EquipmentSlot, ItemStack> slots = byPlayer.get(uuid);
        if (slots == null || slots.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        byPlayer.remove(uuid);
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<EquipmentSlot, ItemStack> entry : slots.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            LockedItemListener.markProgrammatic(uuid, slot);
            if (isReplaceable(inventory.getItem(slot))) {
                inventory.setItem(slot, entry.getValue());
            } else {
                InvUtil.giveItems(player, entry.getValue());
            }
        }
        if (store != null) {
            store.set(ROOT + "." + uuid, null);
            store.save();
        }
    }

    /**
     * Restores the backups of every online player of the owner; the teardown entry
     * point. During teardown the write-through save runs synchronously through the
     * context's shutting-down flag.
     */
    public static void restoreAll(Plugin owner) {
        for (EquipmentBackup backup : BACKUPS.forOwner(owner)) {
            for (UUID uuid : List.copyOf(backup.byPlayer.keySet())) {
                backup.restore(uuid);
            }
        }
    }

    /** Only an empty slot or a locked piece applied by this same owner may be replaced. */
    private boolean isReplaceable(@Nullable ItemStack current) {
        if (current == null || current.getType().isAir()) {
            return true;
        }
        ItemPropertyListener.Match match = ItemPropertyListener.match(current);
        return match != null && match.def().locked() && match.owner() == ctx.plugin();
    }

    private void persist(UUID uuid, EquipmentSlot slot, ItemStack displaced) {
        if (store == null) {
            warnNoStore();
            return;
        }
        store.set(ROOT + "." + uuid + "." + slot.name(),
                ItemSerializer.serializeBase64(displaced));
        store.save();
    }

    private void erase(UUID uuid, EquipmentSlot slot, boolean lastOfPlayer) {
        if (store == null) {
            return;
        }
        if (lastOfPlayer) {
            store.set(ROOT + "." + uuid, null);
        } else {
            store.set(ROOT + "." + uuid + "." + slot.name(), null);
        }
        store.save();
    }

    private void loadPersisted() {
        if (store == null) {
            return;
        }
        ConfigurationSection root = store.getSection(ROOT);
        if (root == null) {
            return;
        }
        for (String uuidKey : root.getKeys(false)) {
            ConfigurationSection slots = root.getConfigurationSection(uuidKey);
            if (slots == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException invalid) {
                warnBroken(uuidKey);
                continue;
            }
            for (String slotKey : slots.getKeys(false)) {
                try {
                    EquipmentSlot slot = EquipmentSlot.valueOf(slotKey);
                    ItemStack real = ItemSerializer.deserializeBase64(slots.getString(slotKey, ""));
                    byPlayer.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>())
                            .put(slot, real);
                } catch (Exception broken) {
                    warnBroken(uuidKey + "." + slotKey);
                }
            }
        }
    }

    private void warnBroken(String entry) {
        ctx.plugin().getLogger().warning("Unreadable equipment backup in "
                + STORE_PATH + " -> '" + entry + "': entry ignored");
    }

    private void warnNoStore() {
        if (warnedNoStore.compareAndSet(false, true)) {
            ctx.plugin().getLogger().warning("EquipmentBackup without a declared yml module: "
                    + "locked-item backups are memory only; a crash without onDisable "
                    + "may lose the real displaced item");
        }
    }

    private static @Nullable SnYml mountStore(Sn ctx) {
        try {
            return ctx.yml().data(STORE_PATH);
        } catch (UnsupportedOperationException noYmlModule) {
            return null;
        }
    }
}
