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
 * over the {@link RegisteredServiceProvider}, so a disabled Vault never leaks a linkage
 * error and a consumer disable releases the hook. This is the ISOLATED hook class: it
 * only links when the Vault API classes are present, so {@code EconomyBridge}
 * instantiates it under a catch of {@code Throwable} and a server without Vault simply
 * runs without this backend. Every write hops to the main thread (Economy is always main
 * thread) and reports the real {@link EconomyResponse#transactionSuccess()} outcome.</p>
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
        return economy().isPresent();
    }

    /**
     * On-use resolution: the Economy provider may register the service AFTER the first
     * access (Vault already enabled, late service); a cached miss re-resolves on the
     * next use instead of staying null for the whole session.
     */
    private java.util.Optional<Economy> economy() {
        java.util.Optional<Economy> resolved = vault.get();
        if (resolved.isEmpty()) {
            vault.invalidate();
            resolved = vault.get();
        }
        return resolved;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        try {
            return economy().map(economy -> economy.getBalance(player)).orElse(0.0D);
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Vault failed to read the balance: " + t);
            return 0.0D;
        }
    }

    @Override
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        return onMain(() -> {
            Economy economy = economy().orElse(null);
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
            Economy economy = economy().orElse(null);
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
            ctx.plugin().getLogger().warning("Vault economy operation failed: " + t);
            return false;
        }
    }

    private static @Nullable Economy resolveProvider() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
}
