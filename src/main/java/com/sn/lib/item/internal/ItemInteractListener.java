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
 * variants dispatch, each
 * running its YML action list through the ActionEngine AND its Java callback. Variants:
 * right-click, left-click, shift-right-click and shift-left-click (a shift variant with
 * behaviour takes priority over its generic click), plus the positional
 * right-click-block, right-click-air, left-click-block and left-click-air lists, which
 * run in addition to the generic one. A successful use then subtracts custom durability;
 * at 0 the break-actions run and the stack leaves the hand that used it.</p>
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
        Function<String, String> resolver =
                token -> ctx.papi().apply(player, SnText.applyLocals(token));
        if (!def.interactRequirement().test(player, resolver)) {
            ctx.actions().run(player, def.denyActions());
            return;
        }
        dispatch(ctx, def, player, item, action);
        applyDurability(ctx, def, match, player, item, hand);
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

    /** Runs the generic (shift-prioritized) variant, then the positional block/air one. */
    private static void dispatch(Sn ctx, ItemDef def, Player player, ItemStack item,
            Action action) {
        boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        boolean block = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK;
        boolean shift = player.isSneaking();
        ClickType click = right
                ? (shift ? ClickType.SHIFT_RIGHT : ClickType.RIGHT)
                : (shift ? ClickType.SHIFT_LEFT : ClickType.LEFT);
        ActionContext context = new ActionContext(player, ctx, null, click, null);
        if (right) {
            if (shift && hasBehaviour(def.shiftRightClickActions(), def.onShiftRightClick())) {
                runVariant(ctx, player, item, def.shiftRightClickActions(),
                        def.onShiftRightClick(), context);
            } else {
                runVariant(ctx, player, item, def.rightClickActions(), def.onRightClick(),
                        context);
            }
            if (block) {
                runVariant(ctx, player, item, def.rightClickBlockActions(),
                        def.onRightClickBlock(), context);
            } else {
                runVariant(ctx, player, item, def.rightClickAirActions(),
                        def.onRightClickAir(), context);
            }
            return;
        }
        if (shift && hasBehaviour(def.shiftLeftClickActions(), def.onShiftLeftClick())) {
            runVariant(ctx, player, item, def.shiftLeftClickActions(), def.onShiftLeftClick(),
                    context);
        } else {
            runVariant(ctx, player, item, def.leftClickActions(), def.onLeftClick(), context);
        }
        if (block) {
            runVariant(ctx, player, item, def.leftClickBlockActions(), def.onLeftClickBlock(),
                    context);
        } else {
            runVariant(ctx, player, item, def.leftClickAirActions(), def.onLeftClickAir(),
                    context);
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

    /** Subtracts damage-per-use; at 0 runs the break-actions and empties the used hand. */
    private static void applyDurability(Sn ctx, ItemDef def, Match match, Player player,
            ItemStack item, EquipmentSlot hand) {
        if (def.durabilityMax() <= 0) {
            return;
        }
        int remaining = DurabilityTracker.damage(match.owner(), def, item,
                def.durabilityDamagePerUse());
        if (remaining > 0) {
            player.getInventory().setItem(hand, item);
            return;
        }
        DurabilityTracker.breakFor(ctx, def, player, item, hand, null);
    }
}
