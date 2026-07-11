package com.sn.lib.item.internal;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLib;
import com.sn.lib.action.ActionContext;
import com.sn.lib.action.ClickSurface;
import com.sn.lib.item.ItemDef;
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
 * a locals plus PAPI resolver, running the deny-actions when unmet; (3) the matching
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
        dispatch(ctx, def, player, item, right, block, shift, context);
        applyDurability(ctx, def, match, player, item, hand, context);
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
