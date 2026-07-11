package com.sn.lib.bridge;

import com.sn.lib.SnExperimental;

/**
 * Terminal result of one bridge send: the typed outcome plus an operator-facing detail
 * (empty on the happy path). Platform-pure on purpose: the Velocity side reuses it.
 *
 * @param result terminal outcome
 * @param detail operator-facing context ("" when there is nothing to add)
 */
@SnExperimental
public record SnDelivery(SnDeliveryResult result, String detail) {

    private static final SnDelivery SENT = new SnDelivery(SnDeliveryResult.SENT, "");

    /** The happy path, cached (no allocation per successful send). */
    public static SnDelivery sent() {
        return SENT;
    }

    public static SnDelivery of(SnDeliveryResult result, String detail) {
        return new SnDelivery(result, detail);
    }

    /** True only for {@link SnDeliveryResult#SENT} / {@link SnDeliveryResult#DELIVERED}. */
    public boolean ok() {
        return result == SnDeliveryResult.SENT || result == SnDeliveryResult.DELIVERED;
    }
}
