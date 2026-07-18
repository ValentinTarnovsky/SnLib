package com.sn.lib.teleport;

/**
 * Outcome of {@link Teleports#request(org.bukkit.entity.Player, org.bukkit.Location,
 * TeleportOptions)}: the state the caller reacts to (message the player, play a sound,
 * abort a command). Every request resolves to exactly one of these; the manager never
 * throws for a rejected request.
 */
public enum TeleportResult {

    /** Warmup started: the warmup message was sent and the teleport is now pending. */
    WARMUP_STARTED,

    /** No warmup ({@code warmupSeconds == 0}): the teleport was dispatched immediately. */
    TELEPORTED,

    /**
     * Rejected by dedup: the player already had a pending teleport, so this request was
     * ignored and never double-scheduled. The caller may message on it (for example a
     * "you are already teleporting" line).
     */
    ALREADY_PENDING,

    /**
     * Rejected by the cooldown category declared in the options: it is still running, so
     * no warmup started. Query {@code sn.cooldowns().remainingMillis(uuid, category)} for
     * the time left.
     */
    ON_COOLDOWN,

    /** Rejected for an invalid request (null player, null target or unloaded target world). */
    FAILED;

    /** True when the request was accepted (warmup started or instant teleport dispatched). */
    public boolean accepted() {
        return this == WARMUP_STARTED || this == TELEPORTED;
    }

    /** True when the request was rejected without dispatching or scheduling anything. */
    public boolean rejected() {
        return !accepted();
    }
}
