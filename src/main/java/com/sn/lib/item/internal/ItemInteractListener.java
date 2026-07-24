package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLib;
import com.sn.lib.action.ActionContext;
import com.sn.lib.action.ClickSurface;
import com.sn.lib.item.ItemDef;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.item.RedeemSpec;
import com.sn.lib.item.internal.ItemPropertyListener.Match;
import com.sn.lib.text.SnText;

/**
 * Single shared listener owned by SnLib that dispatches item interactions. Inscribed in
 * the ListenerHub; the registerEvents call happens UNIQUELY in the SnLibPlugin bootstrap.
 *
 * <p>Only {@link PlayerInteractEvent#getItem()} and {@link PlayerInteractEvent#getHand()}
 * are consulted, so each dispatch belongs to the event whose hand carries the item and a
 * dual-fire (main hand plus offhand) never double-runs one item. Hot-path contract: every
 * handler quick-exits in layers, null/air first, then {@code hasItemMeta()}, then the PDC
 * tag, then logic.</p>
 *
 * <p>Flow per interaction: (0) a right-click whose vanilla auto-equip destination is not
 * the declared {@code equipment-slot} is denied and CUTS the whole flow: no cooldown, no
 * requirement, no dispatch, no durability; (1) the item cooldown ({@code "item:" + id}
 * category) returns silently while cooling down; (2) interact-requirements evaluate with
 * a locals plus PAPI resolver, running the deny-actions when unmet; (2.5) a right-click
 * on an item with a redeemable registration redeems (cancel, consume per spec, invoke
 * handler) and REPLACES variant dispatch and durability for that click - see
 * {@code ItemRegistry.redeemable}; (3) the matching
 * variants dispatch, each running its YML action list through the ActionEngine AND its
 * Java callback. The 12 variants pair up under one uniform shift priority rule: the
 * generic pair (right/left vs shift-right/shift-left) runs first, then the positional
 * pair for the clicked surface (block/air vs shift-block/shift-air), which runs in
 * addition to the generic one. Within each pair, on a shift click a shift variant with
 * behaviour (non-empty list OR callback) runs INSTEAD of its base variant; with
 * {@code shift-overrides-generic: false} BOTH run, shift first and base after. Without
 * shift only the base of each pair runs.</p>
 *
 * <p>Deny-actions and break-actions run with the real {@link ActionContext} of the
 * interaction (ClickType plus BLOCK/AIR surface), so click and surface guards inside
 * those lists evaluate exactly like they do on GUI clicks. A successful use then
 * subtracts custom durability; at 0 the break flow always goes through
 * {@code DurabilityTracker.breakFor(..., context)}, which runs the break-actions and
 * empties the hand that used the item.</p>
 */
public final class ItemInteractListener implements Listener {

    /**
     * Last successful redemption per player, keyed to the server tick: one physical
     * right-click fires the event once per hand, and a player holding the SAME
     * redeemable in both hands would otherwise redeem twice (doubling a capped
     * all-matching sweep). Main-thread only; entries drop on quit.
     */
    private final Map<UUID, RedeemStamp> lastRedeem = new HashMap<>();

    /** One redemption stamp: the server tick it happened on and the redeemed item id. */
    private record RedeemStamp(int tick, String id) {
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastRedeem.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Match match = ItemPropertyListener.match(item);
        if (match == null) {
            return;
        }
        Sn ctx = SnLib.context(match.owner());
        if (ctx == null) {
            return;
        }
        ItemDef def = match.def();
        Player player = event.getPlayer();
        if (denyIncompatibleAutoEquip(event, def, item)) {
            return;
        }
        if (def.cooldownTicks() > 0 && !ctx.cooldowns().tryUseTicks(
                player.getUniqueId(), "item:" + match.id(), def.cooldownTicks())) {
            return;
        }
        boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        boolean block = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK;
        boolean shift = player.isSneaking();
        ClickType click = right
                ? (shift ? ClickType.SHIFT_RIGHT : ClickType.RIGHT)
                : (shift ? ClickType.SHIFT_LEFT : ClickType.LEFT);
        ActionContext context = new ActionContext(player, ctx, null, click,
                block ? ClickSurface.BLOCK : ClickSurface.AIR, null);
        Function<String, String> resolver =
                token -> ctx.papi().apply(player, SnText.applyLocals(token));
        if (!def.interactRequirement().test(player, resolver)) {
            ctx.actions().run(player, def.denyActions(), context);
            return;
        }
        if (right && tryRedeem(ctx, match, event, player, hand)) {
            return;
        }
        dispatch(ctx, def, player, item, right, block, shift, context);
        applyDurability(ctx, def, match, player, item, hand, context);
    }

    /**
     * Redeemable dispatch of one right-click. Returns true when the interaction belongs
     * to a redeemable registration - whether or not units were actually consumed - which
     * cuts variant dispatch and durability for that click. Returns false (normal flow)
     * when the id has no registration or the clicked block is a
     * {@link RedeemSpec#blockedOn()} material, so the block interaction wins there.
     *
     * <p>A use already denied by another plugin ({@code useItemInHand} DENY) is
     * respected: the flow is cut without consuming anything. Otherwise BOTH event
     * results are denied before consuming, so a placeable redeemable is never placed and
     * a clicked container never opens on the redeeming click. One physical click
     * redeems at most ONCE: the same-tick stamp in {@link #lastRedeem} absorbs the
     * second event of a dual fire, so holding the same redeemable in both hands never
     * invokes the handler twice nor doubles a capped all-matching sweep.</p>
     */
    private boolean tryRedeem(Sn ctx, Match match, PlayerInteractEvent event,
            Player player, EquipmentSlot hand) {
        ItemRegistry.RedeemEntry entry = ctx.items().redeemEntry(match.id());
        if (entry == null) {
            return false;
        }
        RedeemSpec spec = entry.spec();
        Block clicked = event.getClickedBlock();
        if (clicked != null && spec.blockedOn().contains(clicked.getType())) {
            return false;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return true;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        int tick = Bukkit.getCurrentTick();
        RedeemStamp stamp = lastRedeem.get(player.getUniqueId());
        if (stamp != null && stamp.tick() == tick && stamp.id().equals(match.id())) {
            return true;
        }
        List<ItemStack> consumed = new ArrayList<>(2);
        int total = consume(ctx, match.id(), player, hand, spec, consumed);
        if (total <= 0) {
            return true;
        }
        lastRedeem.put(player.getUniqueId(), new RedeemStamp(tick, match.id()));
        ctx.debug().log(() -> "redeem: item=" + match.id() + " mode=" + spec.mode()
                + " consumed=" + total + " by " + player.getName());
        entry.handler().redeem(player, total, Collections.unmodifiableList(consumed));
        return true;
    }

    /**
     * Consumes one redemption per the spec's mode, collecting a clone of every removed
     * chunk into {@code consumed}; hand modes re-read the hand slot instead of trusting
     * the event's item snapshot. Same-tick dual-fire dedup is handled upstream by the
     * {@link #lastRedeem} stamp.
     *
     * @return total units consumed, 0 when nothing matched
     */
    private static int consume(Sn ctx, String id, Player player, EquipmentSlot hand,
            RedeemSpec spec, List<ItemStack> consumed) {
        PlayerInventory inventory = player.getInventory();
        switch (spec.mode()) {
            case SINGLE -> {
                ItemStack current = inventory.getItem(hand);
                if (!ctx.items().is(current, id)) {
                    return 0;
                }
                ItemStack unit = current.clone();
                unit.setAmount(1);
                consumed.add(unit);
                if (current.getAmount() <= 1) {
                    inventory.setItem(hand, null);
                } else {
                    current.setAmount(current.getAmount() - 1);
                }
                return 1;
            }
            case HAND_STACK -> {
                ItemStack current = inventory.getItem(hand);
                if (!ctx.items().is(current, id)) {
                    return 0;
                }
                consumed.add(current.clone());
                int amount = current.getAmount();
                inventory.setItem(hand, null);
                return amount;
            }
            case ALL_MATCHING -> {
                int cap = spec.limit() > 0 ? spec.limit() : Integer.MAX_VALUE;
                return consumeMatching(ctx, id, player, cap, consumed);
            }
        }
        return 0;
    }

    /**
     * Inventory-wide consumption of {@link RedeemSpec.Mode#ALL_MATCHING}: every slot
     * (storage, armor, off hand) plus the open cursor, matched by the owner-namespaced
     * item tag, capped at {@code cap} units. Mirrors {@code ItemRegistry.take} while
     * additionally collecting the removed chunks.
     */
    private static int consumeMatching(Sn ctx, String id, Player player, int cap,
            List<ItemStack> consumed) {
        PlayerInventory inventory = player.getInventory();
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize() && removed < cap; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (!ctx.items().is(current, id)) {
                continue;
            }
            int taken = Math.min(current.getAmount(), cap - removed);
            ItemStack chunk = current.clone();
            chunk.setAmount(taken);
            consumed.add(chunk);
            if (taken >= current.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                current.setAmount(current.getAmount() - taken);
            }
            removed += taken;
        }
        if (removed < cap) {
            ItemStack cursor = player.getItemOnCursor();
            if (ctx.items().is(cursor, id)) {
                int taken = Math.min(cursor.getAmount(), cap - removed);
                ItemStack chunk = cursor.clone();
                chunk.setAmount(taken);
                consumed.add(chunk);
                if (taken >= cursor.getAmount()) {
                    player.setItemOnCursor(null);
                } else {
                    cursor.setAmount(cursor.getAmount() - taken);
                }
                removed += taken;
            }
        }
        return removed;
    }

    /**
     * Equipment-slot enforcement, right-click auto-equip vector: when the vanilla
     * auto-equip destination of the material is not the declared slot, the item use is
     * denied and the whole interaction flow is cut (no cooldown, no requirement, no
     * dispatch, no durability).
     *
     * @return true only when the equip was denied
     */
    private static boolean denyIncompatibleAutoEquip(PlayerInteractEvent event, ItemDef def,
            ItemStack item) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        EquipmentSlot declared = def.equipmentSlot();
        if (declared == null) {
            return false;
        }
        EquipmentSlot vanilla = ItemPropertyListener.vanillaEquipSlot(item);
        if (vanilla != null && vanilla != declared) {
            event.setUseItemInHand(Event.Result.DENY);
            return true;
        }
        return false;
    }

    /**
     * Runs the generic (shift-prioritized) pair, then the positional block/air pair for
     * the clicked surface. Resulting rules: (a) the positional phase replicates the
     * generic rule: on shift, a shift positional variant with behaviour (non-empty list
     * OR callback) runs INSTEAD of the plain positional one; without behaviour it falls
     * back to the plain one; (b) the positional pair still runs IN ADDITION to the
     * generic pair; (c) with {@code shift-overrides-generic: false} each pair runs in
     * full (shift first, base after) in BOTH phases; (d) without shift only the base of
     * each pair runs.
     */
    static void dispatch(Sn ctx, ItemDef def, Player player, ItemStack item, boolean right,
            boolean block, boolean shift, ActionContext context) {
        if (right) {
            runPair(ctx, def, player, item, shift,
                    def.shiftRightClickActions(), def.onShiftRightClick(),
                    def.rightClickActions(), def.onRightClick(), context);
            if (block) {
                runPair(ctx, def, player, item, shift,
                        def.shiftRightClickBlockActions(), def.onShiftRightClickBlock(),
                        def.rightClickBlockActions(), def.onRightClickBlock(), context);
            } else {
                runPair(ctx, def, player, item, shift,
                        def.shiftRightClickAirActions(), def.onShiftRightClickAir(),
                        def.rightClickAirActions(), def.onRightClickAir(), context);
            }
            return;
        }
        runPair(ctx, def, player, item, shift,
                def.shiftLeftClickActions(), def.onShiftLeftClick(),
                def.leftClickActions(), def.onLeftClick(), context);
        if (block) {
            runPair(ctx, def, player, item, shift,
                    def.shiftLeftClickBlockActions(), def.onShiftLeftClickBlock(),
                    def.leftClickBlockActions(), def.onLeftClickBlock(), context);
        } else {
            runPair(ctx, def, player, item, shift,
                    def.shiftLeftClickAirActions(), def.onShiftLeftClickAir(),
                    def.leftClickAirActions(), def.onLeftClickAir(), context);
        }
    }

    /**
     * One shift/base pair: on shift, a shift variant with behaviour runs and, under
     * {@link ItemDef#shiftOverridesGeneric()} (default true), replaces the base variant;
     * otherwise the base variant runs too (shift first, base after).
     */
    private static void runPair(Sn ctx, ItemDef def, Player player, ItemStack item, boolean shift,
            List<String> shiftActions, @Nullable BiConsumer<Player, ItemStack> shiftCallback,
            List<String> baseActions, @Nullable BiConsumer<Player, ItemStack> baseCallback,
            ActionContext context) {
        boolean shiftRan = false;
        if (shift && hasBehaviour(shiftActions, shiftCallback)) {
            runVariant(ctx, player, item, shiftActions, shiftCallback, context);
            shiftRan = true;
        }
        if (!shiftRan || !def.shiftOverridesGeneric()) {
            runVariant(ctx, player, item, baseActions, baseCallback, context);
        }
    }

    private static boolean hasBehaviour(List<String> actions,
            @Nullable BiConsumer<Player, ItemStack> callback) {
        return !actions.isEmpty() || callback != null;
    }

    /** One variant: the YML action list through the engine AND the Java callback. */
    private static void runVariant(Sn ctx, Player player, ItemStack item, List<String> actions,
            @Nullable BiConsumer<Player, ItemStack> callback, ActionContext context) {
        if (!actions.isEmpty()) {
            ctx.actions().run(player, actions, context);
        }
        if (callback != null) {
            callback.accept(player, item);
        }
    }

    /**
     * Subtracts damage-per-use; at 0 delegates the break flow (break-actions plus stack
     * removal) to {@code DurabilityTracker.breakFor} with the real interaction context,
     * so click and surface guards inside break-actions evaluate.
     */
    private static void applyDurability(Sn ctx, ItemDef def, Match match, Player player,
            ItemStack item, EquipmentSlot hand, ActionContext context) {
        if (def.durabilityMax() <= 0) {
            return;
        }
        int remaining = DurabilityTracker.damage(match.owner(), def, item,
                def.durabilityDamagePerUse());
        if (remaining > 0) {
            player.getInventory().setItem(hand, item);
            return;
        }
        DurabilityTracker.breakFor(ctx, def, player, item, hand, context);
    }
}
