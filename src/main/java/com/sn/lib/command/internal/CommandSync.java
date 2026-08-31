package com.sn.lib.command.internal;

import java.lang.reflect.Method;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * Reflective bridge to the server's {@code CraftServer#syncCommands()} for {@link
 * BukkitCommandRegistry}, used after the CommandMap has actually been mutated.
 *
 * <p>What the call does depends on the server generation, and both are covered:</p>
 * <ul>
 *   <li><b>Paper 1.20.6+</b> - {@code CraftCommandMap} is backed by the Brigadier-forwarding
 *       map, so a write through {@code Map#put} is already live in the dispatcher and
 *       {@code syncCommands()} only re-sends the command tree to the online players.</li>
 *   <li><b>Paper 1.20.4 / 1.20.5</b> - the known commands are a plain map and the dispatcher
 *       is rebuilt from it ONLY by {@code syncCommands()}, which the server itself calls once
 *       at the end of {@code enablePlugins(POSTWORLD)}. Without this bridge a key registered
 *       after that point - the reload path - stays undispatchable until a restart.</li>
 * </ul>
 *
 * <p>The method is public on {@code CraftServer} but absent from the {@code org.bukkit.Server}
 * interface, so reflection is the only way to reach it from plugin code. The lookup walks up
 * from the concrete server class instead of naming a package, which keeps it working across
 * Paper's unversioned {@code org.bukkit.craftbukkit.CraftServer}, the relocated
 * {@code org.bukkit.craftbukkit.vX_Y_RZ.CraftServer} and any fork that subclasses either.</p>
 *
 * <p>Every failure mode degrades to the caller's client-tree refresh instead of propagating: a
 * server that exposes no such method is a deliberate environment, not a misconfiguration, so it
 * is reported once at FINE and never again.</p>
 */
final class CommandSync {

    /** True once the hierarchy walk ran; the walk happens at most once per server session. */
    private static volatile boolean resolved;

    /** The resolved method, or null when this server exposes none. */
    private static volatile @Nullable Method syncCommands;

    private CommandSync() {
    }

    /**
     * Rebuilds the command dispatcher and re-sends the client trees, and reports whether that
     * happened: a false return means the caller still owes the online players a client-tree
     * refresh. Skipped off the main thread and while the server is stopping - the CommandMap
     * write itself has already landed, and a shutdown teardown has no tree left to refresh.
     */
    static boolean sync() {
        if (!Bukkit.isPrimaryThread() || Bukkit.isStopping()) {
            return false;
        }
        Method method = resolve();
        if (method == null) {
            return false;
        }
        try {
            method.invoke(Bukkit.getServer());
            return true;
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.FINE,
                    "syncCommands() failed; refreshing the client command trees instead", t);
            return false;
        }
    }

    /** The cached {@code syncCommands} method, walking the server class hierarchy on first use. */
    private static @Nullable Method resolve() {
        if (resolved) {
            return syncCommands;
        }
        resolved = true;
        Class<?> serverClass = Bukkit.getServer().getClass();
        for (Class<?> type = serverClass; type != null; type = type.getSuperclass()) {
            try {
                Method found = type.getDeclaredMethod("syncCommands");
                found.setAccessible(true);
                syncCommands = found;
                return found;
            } catch (NoSuchMethodException | RuntimeException absent) {
                // Keep walking: the method is declared on CraftServer, which sits somewhere
                // above the concrete server class on a fork.
            }
        }
        Bukkit.getLogger().fine("No syncCommands() on " + serverClass.getName()
                + "; dynamically registered command keys refresh through the client trees only");
        return null;
    }
}
