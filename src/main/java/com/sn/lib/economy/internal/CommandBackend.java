package com.sn.lib.economy.internal;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import com.sn.lib.Sn;
import com.sn.lib.economy.EconomyBridge;
import com.sn.lib.util.NumberFormatter;

/**
 * Command-dispatch economy backend for servers without Vault.
 *
 * <p>Give and take run console commands built from the configured templates
 * ({@code %player%} and {@code %amount%} tokens). {@code tryTake} reads the balance
 * through the configured PAPI placeholder on the main thread, refuses unaffordable
 * withdrawals, and verifies the post-take balance against an epsilon so the future
 * completes with the real success of the operation. Every operation hops to the main
 * thread (PAPI and command dispatch are main-thread only).</p>
 */
public final class CommandBackend implements EconomyBridge.Backend {

    /** Tolerance for double rounding when comparing balances before and after a take. */
    private static final double EPSILON = 1.0E-3;
    private static final char SECTION = '§';

    private final Sn ctx;
    private final String giveCommand;
    private final String takeCommand;
    private final String balancePlaceholder;
    private final AtomicBoolean warnedUnreadable = new AtomicBoolean();

    public CommandBackend(Sn ctx, String giveCommand, String takeCommand, String balancePlaceholder) {
        this.ctx = ctx;
        this.giveCommand = giveCommand;
        this.takeCommand = takeCommand;
        this.balancePlaceholder = balancePlaceholder;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        double balance = readBalance(player);
        return Double.isNaN(balance) ? 0.0D : balance;
    }

    @Override
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        return onMain(() -> dispatch(giveCommand, player, amount));
    }

    @Override
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
        return onMain(() -> {
            double before = readBalance(player);
            if (Double.isNaN(before) || before + EPSILON < amount) {
                return false;
            }
            if (!dispatch(takeCommand, player, amount)) {
                return false;
            }
            double after = readBalance(player);
            return !Double.isNaN(after) && after <= before - amount + EPSILON;
        });
    }

    /**
     * Balance via the configured PAPI placeholder; NaN when the placeholder does not
     * resolve to a readable number (PAPI absent, unresolved tokens or unparseable text).
     */
    private double readBalance(OfflinePlayer player) {
        Player online = player instanceof Player direct ? direct : player.getPlayer();
        String resolved = ctx.papi().apply(online, balancePlaceholder);
        if (resolved == null || resolved.indexOf('%') >= 0) {
            warnUnreadable(resolved);
            return Double.NaN;
        }
        try {
            return NumberFormatter.parseFormatted(stripDecorations(resolved));
        } catch (NumberFormatException e) {
            warnUnreadable(resolved);
            return Double.NaN;
        }
    }

    private boolean dispatch(String template, OfflinePlayer player, double amount) {
        String name = player.getName();
        if (name == null) {
            ctx.plugin().getLogger().warning(
                    "Jugador sin nombre conocido; comando de economia omitido");
            return false;
        }
        String command = template
                .replace("%player%", name)
                .replace("%amount%", formatAmount(amount));
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Comando de economia fallo ('" + command + "'): " + t);
            return false;
        }
    }

    /** Runs the operation on the main thread, hopping through the owner scheduler if needed. */
    private CompletableFuture<Boolean> onMain(Supplier<Boolean> operation) {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(runSafe(operation));
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            ctx.scheduler().sync(() -> future.complete(runSafe(operation)));
        } catch (IllegalPluginAccessException e) {
            future.complete(false);
        }
        return future;
    }

    private boolean runSafe(Supplier<Boolean> operation) {
        try {
            return Boolean.TRUE.equals(operation.get());
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Operacion del command backend fallo: " + t);
            return false;
        }
    }

    private void warnUnreadable(String resolved) {
        if (warnedUnreadable.compareAndSet(false, true)) {
            ctx.plugin().getLogger().warning("Balance ilegible via '" + balancePlaceholder
                    + "' (resultado: '" + resolved + "'); el command backend no puede verificar balances");
        }
    }

    /** Drops color codes and currency decorations, keeping digits, separators and suffixes. */
    private static String stripDecorations(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == SECTION) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || Character.isLetter(c) || c == '.' || c == ',' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Plain decimal rendering of the amount, never scientific notation. */
    private static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }
}
