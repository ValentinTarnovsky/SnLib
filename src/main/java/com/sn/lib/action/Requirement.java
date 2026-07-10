package com.sn.lib.action;

import java.util.function.Function;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable pre-parsed requirement evaluated against runtime placeholder values.
 *
 * <p>Instances are built once at load by {@link RequirementEngine#parse(java.util.List)}
 * and keep placeholders as raw tokens: every evaluation resolves them again through the
 * supplied resolver, so a single instance serves any player.</p>
 */
@FunctionalInterface
public interface Requirement {

    /**
     * Evaluates the requirement.
     *
     * @param player   player the requirement is checked for; may be null for
     *                 server-level checks
     * @param resolver resolves each operand token to its current value (typically locals
     *                 plus PAPI bound to {@code player}); null leaves tokens untouched
     * @return whether the requirement passes
     */
    boolean test(@Nullable Player player, @Nullable Function<String, String> resolver);
}
