package com.sn.lib.util;

import org.bukkit.entity.Player;

/**
 * Player XP math on the exact vanilla piecewise leveling formula.
 *
 * <p>Total XP to reach a level: {@code level^2 + 6*level} for 0-15,
 * {@code 2.5*level^2 - 40.5*level + 360} for 16-30 and
 * {@code 4.5*level^2 - 162.5*level + 2220} for 31+. Cost of one level:
 * {@code 2*level + 7}, {@code 5*level - 38} and {@code 9*level - 158} for the
 * same brackets, with inverse thresholds at 315 (level 15) and 1395 (level 30)
 * total XP. Use {@link #changeExp(Player, int)} instead of
 * {@link Player#giveExp(int)}: it recomputes level and progress from the total,
 * so it never overlevels and clamps at 0.</p>
 */
public final class Experience {

    /** Total XP at the top of the 0-15 bracket (level 15). */
    private static final long BRACKET_15_TOTAL = 315L;
    /** Total XP at the top of the 15-30 bracket (level 30). */
    private static final long BRACKET_30_TOTAL = 1_395L;

    private Experience() {
    }

    /** Total XP the player currently holds (full levels plus progress bar). */
    public static long getExp(Player player) {
        int level = player.getLevel();
        return getExpFromLevel(level) + Math.round((double) getExpToNext(level) * player.getExp());
    }

    /** Total XP required to reach {@code level} from zero. */
    public static long getExpFromLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        if (level > 30) {
            return Math.round(4.5D * level * level - 162.5D * level + 2_220D);
        }
        if (level > 15) {
            return Math.round(2.5D * level * level - 40.5D * level + 360D);
        }
        return (long) level * level + 6L * level;
    }

    /** XP needed to go from {@code level} to {@code level + 1}. */
    public static int getExpToNext(int level) {
        if (level >= 31) {
            return 9 * level - 158;
        }
        if (level >= 16) {
            return 5 * level - 38;
        }
        return 2 * Math.max(0, level) + 7;
    }

    /** Fractional level for a total XP amount (integer part plus bar progress). */
    public static double getLevelFromExp(long exp) {
        if (exp <= 0L) {
            return 0D;
        }
        if (exp > BRACKET_30_TOTAL) {
            return (Math.sqrt(72D * exp - 54_215D) + 325D) / 18D;
        }
        if (exp > BRACKET_15_TOTAL) {
            return (Math.sqrt(40D * exp - 7_839D) + 81D) / 10D;
        }
        return Math.sqrt(exp + 9D) - 3D;
    }

    /** Whole level for a total XP amount. */
    public static int getIntLevelFromExp(long exp) {
        return (int) getLevelFromExp(exp);
    }

    /**
     * Adds (or subtracts, when negative) XP safely.
     *
     * <p>Recomputes the player's level and progress bar from the resulting
     * total, clamped at 0. Never overlevels.</p>
     */
    public static void changeExp(Player player, int amount) {
        long total = getExp(player) + amount;
        if (total < 0L) {
            total = 0L;
        }
        double levelAndProgress = getLevelFromExp(total);
        int level = (int) levelAndProgress;
        float progress = (float) (levelAndProgress - level);
        if (progress >= 1F) {
            level++;
            progress = 0F;
        }
        if (progress < 0F) {
            progress = 0F;
        }
        player.setLevel(level);
        player.setExp(progress);
    }
}
