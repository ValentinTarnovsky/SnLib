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
 * {@link SnSpec} + {@link SnApi#LEVEL}): this surface only grows within a major
 * version, nothing in it is ever removed or changed incompatibly. Consumers must declare
 * {@code depend: [SnLib]} in their plugin.yml.</p>
 *
 * <p><b>Enable order.</b> The API-level handshake, then {@link #onPreEnable()}, then the
 * context ({@code SnLib.init}, the first step that writes into the plugin folder), then
 * {@link #onInnerEnable()}. A plugin that never got a context (a failed handshake, a refused
 * {@code onPreEnable}, an init that threw) disables without running
 * {@link #onInnerDisable()}: there is nothing to undo.</p>
 */
public abstract class SnPlugin extends JavaPlugin {

    private Sn sn;

    /** Implement it EXACTLY as {@code return SnApi.LEVEL;} - inlines the consumer's compile-time API level. */
    protected abstract int requiredApiLevel();

    @Override
    public final void onEnable() {
        // A context left from an earlier enable of this same instance (a plugin manager's
        // disable + enable) is already shut down: forget it, so a refusal below reaches
        // onDisable with no context instead of tearing the dead one down again.
        this.sn = null;
        int installed = SnLibPlugin.get().apiLevel();
        int required = requiredApiLevel();
        if (installed < required) {
            getLogger().severe("Requires SnLib API level " + required + " (installed: " + installed
                    + "). Update SnLib.jar (restart required): https://github.com/ValentinTarnovsky/SnLib/releases");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // The consumer's own gate runs before SnLib creates config.yml, lang/ or guis/, so a
        // refused enable leaves only the files the gate itself wrote.
        if (!passesPreEnable()) {
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
        // No context means the enable stopped before SnLib.init returned (a failed handshake,
        // a refused onPreEnable, an init that threw): onInnerEnable never ran, so there is
        // nothing for onInnerDisable to undo. This check comes FIRST because a disablePlugin
        // issued inside onPreEnable runs this method synchronously, before the hook returns.
        if (sn == null) {
            return;
        }
        // The teardown window opens BEFORE the consumer's own disable logic: onInnerDisable
        // is the documented place for the final flush, so a save or a join performed there
        // must already count as shutdown work (inline yml writes, no out-of-shutdown join
        // WARN). The teardown itself still runs in the finally.
        sn.beginTeardown();
        try {
            onInnerDisable();
        } finally {
            sn.shutdown();
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
     * Gate of the enable that runs BEFORE SnLib touches the plugin folder; override it to
     * refuse the enable without leaving a single SnLib file behind. It runs after the
     * API-level handshake and before the context exists: {@link #sn()} is still null here, so
     * log through {@link #getLogger()} and use nothing of SnLib. The hook may write files of
     * its OWN (the license.yml it seeds and reads), never the ones SnLib manages
     * (config.yml, lang/, guis/).
     *
     * <p>Return {@code false} to refuse: the plugin ends disabled, SnLib creates no context
     * and writes no config.yml, lang/ or guis/, and neither {@link #onInnerEnable()} nor
     * {@link #onInnerDisable()} runs. The hook logs its own reason; the library adds no line.
     * A hook that disabled the plugin counts as a refusal whatever it returned. Throwing
     * follows the {@link #onInnerEnable()} rules: after disabling, ONE line
     * {@code "Enable aborted: <reason>"} (the throwable at {@code FINE}); without disabling,
     * {@code SEVERE} with the full stack trace, after which the library disables the plugin.</p>
     *
     * <p>This is the place for a license gate, so an unlicensed install shows only the
     * license file: an override whose body is
     * {@code return LicenseManager.init(this, "myplugin");}, the manager seeding license.yml
     * and logging why it refused.</p>
     *
     * <p>A consumer that overrides this hook requires SnLib API level 25 (release 1.38.0):
     * an older SnLib would never call it, and the handshake refuses such a plugin first.</p>
     *
     * @return {@code true} (the default) to go on with the enable, {@code false} to refuse it
     */
    protected boolean onPreEnable() {
        return true;
    }

    /**
     * Consumer enable logic; runs after the handshake, {@link #onPreEnable()} and the context
     * initialization.
     *
     * <p><b>Refusing to enable.</b> A gate that must run before SnLib writes a single file
     * (a license check) belongs in {@link #onPreEnable()}. A consumer that decides here that
     * it must not run (an absent requirement found through its context) disables itself with
     * {@code getServer().getPluginManager().disablePlugin(this)} and then leaves this
     * method, either with a plain {@code return} or by throwing - both are supported and
     * both are reported as ONE line, {@code "Enable aborted: <reason>"}, because the
     * refusal already logged its own reason. The throwable of the throwing form is kept
     * at {@code FINE}. By then the context already exists: its files are on disk and
     * {@link #onInnerDisable()} runs.</p>
     *
     * <p>Throwing WITHOUT disabling first stays what it always was: an unexpected failure,
     * logged {@code SEVERE} with the full stack trace, after which the library disables the
     * plugin rather than leaving it half-initialized.</p>
     */
    protected abstract void onInnerEnable();

    /**
     * Consumer disable logic; runs before the context teardown. Optional. It runs only when
     * the plugin got a context: a failed handshake, a refused {@link #onPreEnable()} or a
     * context init that threw disables the plugin without calling it. The context is
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

    /**
     * SnLib context of this plugin; available from {@link #onInnerEnable()} on (null inside
     * {@link #onPreEnable()}).
     */
    public final Sn sn() {
        return sn;
    }

    /**
     * Runs {@link #onPreEnable()} and reports whether the enable goes on, applying the
     * outcome {@link #preEnableOutcome} decides: disabling the plugin where the hook did not,
     * and logging only where the hook's own log cannot have covered it.
     */
    private boolean passesPreEnable() {
        boolean proceed = false;
        Throwable failure = null;
        try {
            proceed = onPreEnable();
        } catch (Throwable t) {
            failure = t;
        }
        switch (preEnableOutcome(failure != null, proceed, isEnabled())) {
            case PROCEED -> {
                return true;
            }
            case DISABLE_QUIET -> getServer().getPluginManager().disablePlugin(this);
            case ABORT_ONE_LINE -> {
                getLogger().severe("Enable aborted: " + reasonOf(failure));
                getLogger().log(Level.FINE, "Enable aborted", failure);
            }
            case FAIL_TRACE_AND_DISABLE -> {
                getLogger().log(Level.SEVERE, "onPreEnable failed", failure);
                getServer().getPluginManager().disablePlugin(this);
            }
            case ABORT_QUIET -> {
            }
        }
        return false;
    }

    /** What the enable does after {@link #onPreEnable()}; see {@link #preEnableOutcome}. */
    enum PreEnableOutcome {
        /** The hook returned true and left the plugin enabled: the context comes next. */
        PROCEED,
        /** The hook refused and the plugin is already disabled: nothing left to do or log. */
        ABORT_QUIET,
        /** The hook disabled the plugin and threw: one "Enable aborted" line, trace at FINE. */
        ABORT_ONE_LINE,
        /** The hook threw without disabling: a bug, SEVERE with the trace, then disable. */
        FAIL_TRACE_AND_DISABLE,
        /** The hook returned false without disabling: the library disables, adding no line. */
        DISABLE_QUIET
    }

    /**
     * Pure decision behind {@link #passesPreEnable()}, mirroring the {@link #onInnerEnable()}
     * abort rules: a throw is judged by whether the plugin is still enabled ({@code returned}
     * is then irrelevant); a normal return proceeds only when it is true AND the plugin is
     * still enabled.
     *
     * @param threw        whether the hook threw
     * @param returned     the hook's return value when it did not throw
     * @param stillEnabled whether the plugin was still enabled once the hook finished
     */
    static PreEnableOutcome preEnableOutcome(boolean threw, boolean returned,
            boolean stillEnabled) {
        if (threw) {
            return stillEnabled ? PreEnableOutcome.FAIL_TRACE_AND_DISABLE
                    : PreEnableOutcome.ABORT_ONE_LINE;
        }
        if (!stillEnabled) {
            return PreEnableOutcome.ABORT_QUIET;
        }
        return returned ? PreEnableOutcome.PROCEED : PreEnableOutcome.DISABLE_QUIET;
    }

    /** One-line reason of an aborted enable; never empty, the message may be absent. */
    private static String reasonOf(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
