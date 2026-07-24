package com.sn.lib;

/**
 * Public API level of this SnLib build.
 *
 * <p>Policy: {@link #LEVEL} increases by exactly 1 on EVERY release that adds new public
 * methods or classes to the API; the API surface is frozen under an additive-only japicmp
 * gate. This is the number every consumer handshakes against through
 * {@code SnPlugin#requiredApiLevel()}: the required level is inlined into the consumer's
 * bytecode at compile time, so a consumer built against a newer level than the installed
 * SnLib.jar disables itself cleanly instead of failing with {@code NoSuchMethodError}.</p>
 *
 * <p>History: LEVEL 1 = release 1.0.0; LEVEL 2 = release 1.1.0; LEVEL 3 = release 1.4.0
 * (shared multi-plugin releases repo support in UpdateChecker); LEVEL 4 = release 1.8.0
 * (ItemRegistry take/removeAll, SnYml comment write surface, lang interactive-tag drift
 * WARN); LEVEL 5 = release 1.10.0 (SubCommandBuilder helpVisible help-only visibility
 * flag); LEVEL 6 = release 1.11.0 (SnItem itemModel 1.21.2+ item_model component
 * support); LEVEL 7 = release 1.12.0 (redeemable items via ItemRegistry.redeemable with
 * RedeemSpec/RedeemHandler; Args.intMin/doubleMin open-ended numeric factories;
 * k/m/b/t/qa/qi suffix parsing in every numeric arg; snlib.number-too-small message
 * key; SnItem lore newline split for multi-line placeholder values).</p>
 */
public final class SnApi {

    /** API level of this build. Bumped by 1 on every release that grows the public API. */
    public static final int LEVEL = 7;

    private SnApi() {
    }
}
