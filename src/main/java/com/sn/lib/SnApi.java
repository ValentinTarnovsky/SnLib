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
 * (shared multi-plugin releases repo support in UpdateChecker).</p>
 */
public final class SnApi {

    /** API level of this build. Bumped by 1 on every release that grows the public API. */
    public static final int LEVEL = 3;

    private SnApi() {
    }
}
