package com.sn.lib.economy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.economy.internal.CommandBackend;
import com.sn.lib.economy.internal.VaultBackend;

/**
 * Economy service of a consumer context, reached through {@code sn.economy()}.
 *
 * <p>Operations resolve the first available backend in registration order: Vault
 * (registered at construction), then the command backend configured through
 * {@link #useCommandBackend}, then any custom {@link Backend} added via
 * {@link #registerBackend}. With no backend available, every operation warns once and
 * reports failure ({@code 0} balance, {@code false} futures).</p>
 *
 * <p>Economy access is main-thread only: {@link #getBalance(Player)} must run on the
 * main thread (off it the call returns {@code 0} with one WARN), while the write
 * operations may be called from any thread because every backend hops to the main
 * thread on its own.</p>
 */
public final class EconomyBridge {

    /**
     * Pluggable economy backend. Contract: Economy access always happens on the main
     * thread. {@link #getBalance} is only invoked on the main thread, and write
     * operations invoked off the main thread must hop to it themselves, as the built-in
     * backends do.
     */
    public interface Backend {

        /** Current balance of the player; main-thread only. */
        double getBalance(OfflinePlayer player);

        /** Deposits {@code amount}; the future completes with the real success. */
        CompletableFuture<Boolean> give(OfflinePlayer player, double amount);

        /**
         * Withdraws {@code amount} only if the player can afford it; the future
         * completes with the real success of the withdrawal.
         */
        CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount);

        /** True when this backend can serve operations right now. */
        default boolean available() {
            return true;
        }
    }

    private static final String VAULT = "vault";
    private static final String COMMAND = "command";

    private final Sn ctx;
    private final Map<String, Backend> backends = new LinkedHashMap<>();
    private final AtomicBoolean warnedNoBackend = new AtomicBoolean();
    private final AtomicBoolean warnedOffMain = new AtomicBoolean();

    /** Creates the bridge for the given context and registers the Vault backend. */
    public EconomyBridge(Sn ctx) {
        this.ctx = ctx;
        registerBackend(VAULT, new VaultBackend(ctx));
    }

    /**
     * Current balance of the player through the active backend; main-thread only. Off
     * the main thread, or with no backend available, returns {@code 0} with one WARN.
     */
    public double getBalance(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            if (warnedOffMain.compareAndSet(false, true)) {
                ctx.plugin().getLogger().warning(
                        "getBalance llamado fuera del main thread; devolviendo 0 (Economy siempre main thread)");
            }
            return 0.0D;
        }
        Backend backend = active();
        if (backend == null) {
            warnNoBackend();
            return 0.0D;
        }
        return backend.getBalance(player);
    }

    /**
     * Deposits {@code amount} to the player. The future completes with the real success
     * of the operation; false on an invalid amount (non-finite or not positive) or with
     * no backend available.
     */
    public CompletableFuture<Boolean> give(Player player, double amount) {
        if (!validAmount(amount)) {
            return CompletableFuture.completedFuture(false);
        }
        Backend backend = active();
        if (backend == null) {
            warnNoBackend();
            return CompletableFuture.completedFuture(false);
        }
        return backend.give(player, amount);
    }

    /**
     * Withdraws {@code amount} from the player only if affordable. The future completes
     * with the REAL success of the withdrawal; false on an invalid amount (non-finite or
     * not positive), insufficient funds or no backend available.
     */
    public CompletableFuture<Boolean> tryTake(Player player, double amount) {
        if (!validAmount(amount)) {
            return CompletableFuture.completedFuture(false);
        }
        Backend backend = active();
        if (backend == null) {
            warnNoBackend();
            return CompletableFuture.completedFuture(false);
        }
        return backend.tryTake(player, amount);
    }

    /**
     * Registers (or replaces) a backend under {@code name}. Selection walks backends in
     * first-registration order, so Vault keeps priority, the command backend follows and
     * custom backends come after unless they replace one of those names.
     */
    public synchronized void registerBackend(String name, Backend backend) {
        backends.put(name.toLowerCase(Locale.ROOT), backend);
    }

    /**
     * Configures the command-dispatch fallback backend. The command templates accept the
     * tokens {@code %player%} and {@code %amount%}; {@code balancePlaceholder} is the
     * PAPI placeholder that reports the player's balance (used by {@code tryTake} to
     * verify affordability and the post-take result).
     */
    public void useCommandBackend(String giveCommand, String takeCommand, String balancePlaceholder) {
        registerBackend(COMMAND, new CommandBackend(ctx, giveCommand, takeCommand, balancePlaceholder));
    }

    /** True when at least one registered backend is available. */
    public boolean available() {
        return active() != null;
    }

    private synchronized @Nullable Backend active() {
        for (Backend backend : backends.values()) {
            if (backend.available()) {
                return backend;
            }
        }
        return null;
    }

    private boolean validAmount(double amount) {
        if (Double.isFinite(amount) && amount > 0.0D) {
            return true;
        }
        ctx.debug().log(() -> "Cantidad de economia invalida: " + amount);
        return false;
    }

    private void warnNoBackend() {
        if (warnedNoBackend.compareAndSet(false, true)) {
            ctx.plugin().getLogger().warning("No hay backend de economia disponible: instala Vault"
                    + " o configura useCommandBackend(...); las operaciones devuelven false");
        }
    }
}
