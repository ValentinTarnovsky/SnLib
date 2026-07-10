package com.sn.lib.economy.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.economy.EconomyBridge;
import com.sn.lib.hook.SoftDependency;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * Vault-backed economy backend of one consumer context.
 *
 * <p>The {@link Economy} provider resolves through a per-owner {@link SoftDependency}
 * over the {@link RegisteredServiceProvider}, so a missing or disabled Vault never leaks
 * a linkage error and a consumer disable releases the hook. Every write hops to the main
 * thread (Economy is always main thread) and reports the real
 * {@link EconomyResponse#transactionSuccess()} outcome.</p>
 */
public final class VaultBackend implements EconomyBridge.Backend {

    private final Sn ctx;
    private final SoftDependency<Economy> vault;

    public VaultBackend(Sn ctx) {
        this.ctx = ctx;
        this.vault = SoftDependency.of(ctx.plugin(), "Vault", VaultBackend::resolveProvider);
    }

    @Override
    public boolean available() {
        return vault.isAvailable();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        try {
            return vault.get().map(economy -> economy.getBalance(player)).orElse(0.0D);
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Vault fallo al leer el balance: " + t);
            return 0.0D;
        }
    }

    @Override
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        return onMain(() -> {
            Economy economy = vault.get().orElse(null);
            if (economy == null) {
                return false;
            }
            EconomyResponse response = economy.depositPlayer(player, amount);
            return response.transactionSuccess();
        });
    }

    @Override
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
        return onMain(() -> {
            Economy economy = vault.get().orElse(null);
            if (economy == null || !economy.has(player, amount)) {
                return false;
            }
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            return response.transactionSuccess();
        });
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
            ctx.plugin().getLogger().warning("Operacion de economia Vault fallo: " + t);
            return false;
        }
    }

    private static @Nullable Economy resolveProvider() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
}
