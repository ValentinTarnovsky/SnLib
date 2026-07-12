package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.item.ItemDef;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.scheduler.TaskHandle;

/**
 * Per-context sync timer applying the held-effects of registered items. It is a TIMER,
 * not a listener: it never goes through the ListenerHub.
 *
 * <p>Lazy by design: the timer only starts once a tracked definition declares at least
 * one held-effect line. Every 40 ticks it checks main hand, offhand and worn armor of
 * each online player against the definitions of its own context (owner-namespaced PDC
 * id, so contexts never interfere) and applies the matching {@link PotionEffect}s,
 * ambient and without particles, with a duration that outlives the sweep period so the
 * effect is seamless while held and expires on its own when released.</p>
 *
 * <p>Effect lines ({@code "EFFECT amplifier"}) are parsed ONCE when the definition is
 * tracked, never per tick; invalid effect ids or amplifiers WARN once and are skipped.</p>
 */
public final class HeldEffectsTask {

    /** Sweep period in ticks. */
    private static final long PERIOD_TICKS = 40L;

    /** Effect duration in ticks: 60 plus a margin so effects never lapse between sweeps. */
    private static final int DURATION_TICKS = 80;

    private final Sn ctx;
    private final ItemRegistry registry;
    private final Map<String, Effects> byId = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private @Nullable TaskHandle handle;

    public HeldEffectsTask(Sn ctx, ItemRegistry registry) {
        this.ctx = ctx;
        this.registry = registry;
    }

    /**
     * Tracks (or re-tracks) a definition: parses its held-effect lines once and starts
     * the timer lazily on the first definition that has any. A definition without
     * held-effects drops any previous tracking of the same id.
     */
    public void track(String id, ItemDef def) {
        List<PotionEffect> mainhand = parseLines(id, def.heldEffectsMainhand());
        List<PotionEffect> offhand = parseLines(id, def.heldEffectsOffhand());
        List<PotionEffect> armor = parseLines(id, def.heldEffectsArmor());
        if (mainhand.isEmpty() && offhand.isEmpty() && armor.isEmpty()) {
            byId.remove(id);
            return;
        }
        byId.put(id, new Effects(mainhand, offhand, armor));
        ensureStarted();
    }

    /** Cancels the timer; the next tracked definition with held-effects restarts it. */
    public synchronized void stop() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }

    /** Restarts the timer when any tracked definition remains; the reload re-track path. */
    public synchronized void restart() {
        if (!byId.isEmpty()) {
            ensureStarted();
        }
    }

    private synchronized void ensureStarted() {
        if (handle == null || handle.isCancelled()) {
            handle = ctx.scheduler().timer(PERIOD_TICKS, PERIOD_TICKS, this::tick);
        }
    }

    private void tick() {
        if (byId.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerInventory inventory = player.getInventory();
            apply(player, inventory.getItemInMainHand(), Effects::mainhand);
            apply(player, inventory.getItemInOffHand(), Effects::offhand);
            for (ItemStack piece : inventory.getArmorContents()) {
                apply(player, piece, Effects::armor);
            }
        }
    }

    /** Layered quick-exit: null/air, hasItemMeta, PDC id of this context, tracked effects. */
    private void apply(Player player, @Nullable ItemStack stack,
            Function<Effects, List<PotionEffect>> position) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return;
        }
        String id = registry.idOf(stack);
        if (id == null) {
            return;
        }
        Effects effects = byId.get(id);
        if (effects == null) {
            return;
        }
        for (PotionEffect effect : position.apply(effects)) {
            player.addPotionEffect(effect);
        }
    }

    private List<PotionEffect> parseLines(String id, List<String> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<PotionEffect> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            PotionEffectType type = resolveEffect(parts[0]);
            if (type == null) {
                warnOnce(id + ":" + parts[0], "Invalid held effect '" + parts[0]
                        + "' on item '" + id + "'; ignored");
                continue;
            }
            int amplifier = 0;
            if (parts.length > 1) {
                try {
                    amplifier = Math.max(0, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException notANumber) {
                    warnOnce(id + ":amp:" + parts[1], "Invalid amplifier '" + parts[1]
                            + "' on item '" + id + "'; using 0");
                }
            }
            out.add(new PotionEffect(type, DURATION_TICKS, amplifier, true, false));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("deprecation") // getByName resolves legacy names (FAST_DIGGING).
    private static @Nullable PotionEffectType resolveEffect(String id) {
        NamespacedKey key = NamespacedKey.fromString(
                id.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        if (key != null) {
            PotionEffectType byKey = Registry.EFFECT.get(key);
            if (byKey != null) {
                return byKey;
            }
        }
        return PotionEffectType.getByName(id.trim().toUpperCase(Locale.ROOT));
    }

    private void warnOnce(String tag, String message) {
        if (warned.add(tag)) {
            ctx.plugin().getLogger().warning(message);
        }
    }

    /** Pre-parsed effect lists of one definition, by wear position. */
    private record Effects(List<PotionEffect> mainhand, List<PotionEffect> offhand,
            List<PotionEffect> armor) {
    }
}
