package com.sn.lib.item;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Material;

/**
 * Immutable consumption policy of a redeemable item, registered through
 * {@link ItemRegistry#redeemable(String, RedeemSpec, RedeemHandler)}.
 *
 * <p>The spec decides how much one right-click consumes ({@link Mode}), an optional cap
 * for the inventory-wide mode, and an optional set of clicked-block materials on which
 * redemption steps aside so the block interaction wins (containers, workstations). The
 * dispatch itself lives in SnLib's shared interact listener: both
 * {@code RIGHT_CLICK_AIR} and {@code RIGHT_CLICK_BLOCK} redeem, sneaking or not, from
 * either hand, and the interaction is cancelled so a placeable item is never placed
 * instead of redeemed.</p>
 */
public final class RedeemSpec {

    /** How much of the redeemed item one right-click consumes. */
    public enum Mode {
        /** Exactly one unit from the used hand. */
        SINGLE,
        /** The whole stack held in the used hand. */
        HAND_STACK,
        /** Every matching stack of the inventory and open cursor, optionally capped. */
        ALL_MATCHING
    }

    private final Mode mode;
    private final int limit;
    private final Set<Material> blockedOn;

    private RedeemSpec(Mode mode, int limit, Set<Material> blockedOn) {
        this.mode = mode;
        this.limit = limit;
        this.blockedOn = blockedOn;
    }

    /** One unit per redemption, taken from the used hand. */
    public static RedeemSpec single() {
        return new RedeemSpec(Mode.SINGLE, 0, Set.of());
    }

    /** The whole held stack per redemption. */
    public static RedeemSpec handStack() {
        return new RedeemSpec(Mode.HAND_STACK, 0, Set.of());
    }

    /** Every matching stack of the inventory per redemption, uncapped. */
    public static RedeemSpec allMatching() {
        return new RedeemSpec(Mode.ALL_MATCHING, 0, Set.of());
    }

    /**
     * Every matching stack of the inventory per redemption, consuming at most
     * {@code limit} units; a non-positive limit means uncapped.
     */
    public static RedeemSpec allMatching(int limit) {
        return new RedeemSpec(Mode.ALL_MATCHING, Math.max(0, limit), Set.of());
    }

    /**
     * Copy of this spec that skips redemption when the clicked block's type is listed,
     * so the vanilla block interaction runs instead (open the chest, use the anvil).
     * An empty or null collection clears the set.
     */
    public RedeemSpec blockedOn(Collection<Material> materials) {
        Set<Material> copy = materials == null || materials.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(materials));
        return new RedeemSpec(mode, limit, copy);
    }

    /** Consumption mode of one redemption. */
    public Mode mode() {
        return mode;
    }

    /** Unit cap of {@link Mode#ALL_MATCHING} redemptions; 0 means uncapped. */
    public int limit() {
        return limit;
    }

    /** Clicked-block materials on which redemption steps aside; never null. */
    public Set<Material> blockedOn() {
        return blockedOn;
    }

    @Override
    public String toString() {
        return "RedeemSpec[" + mode + (limit > 0 ? ", limit=" + limit : "")
                + (blockedOn.isEmpty() ? "" : ", blockedOn=" + blockedOn.size()) + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedeemSpec spec && mode == spec.mode && limit == spec.limit
                && blockedOn.equals(spec.blockedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, limit, blockedOn);
    }
}
