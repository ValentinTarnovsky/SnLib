package com.sn.lib;

import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mandatory base class of every SnLib consumer and the ONLY initialization path of the
 * library: {@code SnLib.init} is package-private, so extending this class is the single
 * public way to obtain a context.
 *
 * <p><b>Bytecode-side API-level handshake.</b> {@link #requiredApiLevel()} must be
 * implemented exactly as {@code return SnApi.LEVEL;}: javac inlines the literal the
 * consumer compiled against into the CONSUMER's own class file. At runtime that constant
 * is compared with {@link SnLibPlugin#apiLevel()}, the level inlined in the installed
 * SnLib.jar. A consumer built against a newer API level than the installed jar disables
 * itself cleanly with an update message instead of failing later with
 * {@code NoSuchMethodError} or {@code NoClassDefFoundError}.</p>
 *
 * <p>Part of the frozen entrypoint ({@code SnPlugin} + {@code requiredApiLevel()} +
 * {@link SnSpec} + {@link SnApi#LEVEL}): this surface never changes within a major
 * version. Consumers must declare {@code depend: [SnLib]} in their plugin.yml.</p>
 */
public abstract class SnPlugin extends JavaPlugin {

    private Sn sn;

    /** Implement it EXACTLY as {@code return SnApi.LEVEL;} - inlines the consumer's compile-time API level. */
    protected abstract int requiredApiLevel();

    @Override
    public final void onEnable() {
        int installed = SnLibPlugin.get().apiLevel();
        int required = requiredApiLevel();
        if (installed < required) {
            getLogger().severe("Requires SnLib API level " + required + " (installed: " + installed
                    + "). Update SnLib.jar (restart required): https://github.com/ValentinTarnovsky/SnLib/releases");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.sn = SnLib.init(this, buildSpec());
        try {
            onInnerEnable();
        } catch (Throwable t) {
            // A consumer that disabled itself INSIDE onInnerEnable (a license gate, a
            // missing requirement) and then threw only did so to cut the enable short:
            // the reason was already logged where the decision was taken, so one line
            // closes it instead of a stack trace that reads like a bug. The detail stays
            // available at FINE. Throwing WITHOUT disabling is still a bug and keeps the
            // full trace below.
            if (!isEnabled()) {
                getLogger().severe("Enable aborted: " + reasonOf(t));
                getLogger().log(Level.FINE, "Enable aborted", t);
                return;
            }
            getLogger().log(Level.SEVERE, "onInnerEnable failed", t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Same abort, expressed as a plain return: the teardown already ran, so there is
        // no command tree left to seed.
        if (!isEnabled()) {
            return;
        }
        // The command tree exists only once the consumer registered its roots, so the
        // descriptions and argument labels are seeded into the lang file and applied here.
        sn.commands().applyLang();
    }

    @Override
    public final void onDisable() {
        // The teardown window opens BEFORE the consumer's own disable logic: onInnerDisable
        // is the documented place for the final flush, so a save or a join performed there
        // must already count as shutdown work (inline yml writes, no out-of-shutdown join
        // WARN). The teardown itself still runs in the finally.
        if (sn != null) {
            sn.beginTeardown();
        }
        try {
            onInnerDisable();
        } finally {
            if (sn != null) {
                sn.shutdown();
            }
        }
    }

    /**
     * Modules this consumer declares; override to opt in
     * ({@code SnSpec.builder().config("config.yml").lang()...build()}). The default
     * declares no optional module.
     */
    protected SnSpec buildSpec() {
        return SnSpec.builder().build();
    }

    /**
     * Consumer enable logic; runs after the handshake and the context initialization.
     *
     * <p><b>Refusing to enable.</b> A consumer that decides it must not run (an invalid
     * license, an absent requirement) disables itself with
     * {@code getServer().getPluginManager().disablePlugin(this)} and then leaves this
     * method, either with a plain {@code return} or by throwing - both are supported and
     * both are reported as ONE line, {@code "Enable aborted: <reason>"}, because the
     * refusal already logged its own reason. The throwable of the throwing form is kept
     * at {@code FINE}.</p>
     *
     * <p>Throwing WITHOUT disabling first stays what it always was: an unexpected failure,
     * logged {@code SEVERE} with the full stack trace, after which the library disables the
     * plugin rather than leaving it half-initialized.</p>
     */
    protected abstract void onInnerEnable();

    /**
     * Consumer disable logic; runs before the context teardown. Optional. The context is
     * already inside its teardown window here ({@code sn().isShuttingDown()} is true), so
     * a final flush behaves like teardown work: {@code SnYml.save()} writes inline and a
     * {@code SnFuture.join()} on the main thread is allowed without a WARN. Every module
     * is still live: the pool is open and joins complete normally.
     *
     * <p>Flush by joining the future the database module returned, never a chained one: a
     * {@code SnFuture.chainSync} result can only be completed by a main-thread task, and by
     * this point the enabled flag is already cleared and the scheduler is about to be
     * cancelled, so that task will never run.</p>
     */
    protected void onInnerDisable() {
    }

    /** SnLib context of this plugin; available from {@link #onInnerEnable()} on. */
    public final Sn sn() {
        return sn;
    }

    /** One-line reason of an aborted enable; never empty, the message may be absent. */
    private static String reasonOf(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
