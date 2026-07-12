package com.sn.lib.bridge.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.bridge.wire.Verbs;
import com.sn.lib.bridge.wire.WireIds;
import com.sn.lib.text.SnText;
import com.sn.lib.util.SoundUtil;

/**
 * Tier 2 executor: SnLib itself serves the generic verbs on the {@code snlib:bridge}
 * channel, so a proxy-only plugin needs no Paper companion jar. Every verb is a
 * responder: it runs on the main thread and answers a {@link Verbs.Ack} correlated to
 * the request msgId, so the proxy future ALWAYS resolves typed, never silent.
 *
 * <p>Security model of the console verb: HMAC is the transport floor; on top of it the
 * BACKEND-authoritative {@link ConsoleAllowlist} (anchored patterns, empty by default =
 * deny all) plus a per-second rate limit decide what actually reaches
 * {@code Bukkit.dispatchCommand}. Denials answer a typed DENIED ack, which the proxy
 * surfaces as a rate-limited NACK-style warn - never ghost silence.</p>
 */
final class VerbService {

    /** Verb vocabulary versions announced in HELLO (proxy gates on presence). */
    static final Map<String, Integer> CAPABILITIES = Map.of(
            "console", 1, "message", 1, "title", 1, "actionbar", 1,
            "sound", 1, "bossbar", 1, "actions", 1);

    /** Prefix isolating verb-created bossbars from bars the backend plugins own. */
    private static final String BAR_ID_PREFIX = "snbridge/";

    /**
     * Fail-closed set of action tags the {@code actions} verb may run: presentation and
     * feedback only, NO command dispatch or op toggle. A proxy that wants to run commands
     * must use the console verb, which is allowlist-gated. Any line whose effective tag
     * is outside this set denies the whole verb (unknown tags too), so the ActionEngine's
     * {@code [console]}/{@code [player-as-op]}/{@code [player]}/{@code [connect]} handlers
     * are never reachable over the bridge.
     */
    private static final Set<String> SAFE_ACTION_TAGS = Set.of(
            "message", "broadcastmessage", "actionbar", "title", "sound", "close",
            "particle", "potion", "remove-item", "next-page", "previous-page", "set-page",
            "refresh-page", "refresh-menu");

    private final Sn selfCtx;
    private final ConsoleAllowlist allowlist;
    private final int ratePerSecond;
    private long rateWindowStart;
    private int rateUsed;

    private VerbService(Sn selfCtx, ConsoleAllowlist allowlist, int ratePerSecond) {
        this.selfCtx = selfCtx;
        this.allowlist = allowlist;
        this.ratePerSecond = ratePerSecond;
    }

    /** Wires every verb responder into the internal snlib:bridge channel core. */
    static void install(Sn selfCtx, ChannelCore core) {
        List<String> invalid = new ArrayList<>(2);
        ConsoleAllowlist allowlist = ConsoleAllowlist.parse(
                selfCtx.yml().config().getStringList("bridge.console-allowlist", List.of()),
                invalid);
        for (String line : invalid) {
            selfCtx.plugin().getLogger().warning("[SnBridge] console-allowlist pattern rejected: "
                    + line);
        }
        int rate = Math.max(1,
                selfCtx.yml().config().getInt("bridge.console-rate-limit-per-second", 10));
        VerbService service = new VerbService(selfCtx, allowlist, rate);

        core.registry().register(Verbs.Console.TYPE, Verbs.Message.TYPE, Verbs.Title.TYPE,
                Verbs.Actionbar.TYPE, Verbs.Sound.TYPE, Verbs.Bossbar.TYPE, Verbs.Actions.TYPE,
                Verbs.Ack.TYPE, Verbs.AllowlistReq.TYPE, Verbs.Allowlist.TYPE);

        core.respond(WireIds.VERB_CONSOLE, Verbs.Ack.TYPE,
                (carrier, request) -> service.console((Verbs.Console) request));
        core.respond(WireIds.VERB_MESSAGE, Verbs.Ack.TYPE,
                (carrier, request) -> service.message((Verbs.Message) request));
        core.respond(WireIds.VERB_TITLE, Verbs.Ack.TYPE,
                (carrier, request) -> service.title((Verbs.Title) request));
        core.respond(WireIds.VERB_ACTIONBAR, Verbs.Ack.TYPE,
                (carrier, request) -> service.actionbar((Verbs.Actionbar) request));
        core.respond(WireIds.VERB_SOUND, Verbs.Ack.TYPE,
                (carrier, request) -> service.sound((Verbs.Sound) request));
        core.respond(WireIds.VERB_BOSSBAR, Verbs.Ack.TYPE,
                (carrier, request) -> service.bossbar((Verbs.Bossbar) request));
        core.respond(WireIds.VERB_ACTIONS, Verbs.Ack.TYPE,
                (carrier, request) -> service.actions((Verbs.Actions) request));
        core.respond(WireIds.VERB_ALLOWLIST_REQ, Verbs.Allowlist.TYPE,
                (carrier, request) -> new Verbs.Allowlist(allowlist.effectivePatterns()));
    }

    // -------------------------------------------------------
    // Verb executions (main thread, responder contract)
    // -------------------------------------------------------

    private Verbs.Ack console(Verbs.Console verb) {
        if (!rateAllows()) {
            return new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST,
                    "console rate limit exceeded (" + ratePerSecond + "/s)");
        }
        String matched = allowlist.match(verb.command());
        if (matched == null) {
            return new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST,
                    "no allowlist pattern matches: " + verb.command());
        }
        selfCtx.debug().log("bridge", () -> "console verb '" + verb.command()
                + "' allowed by pattern '" + matched + "'");
        boolean known = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), verb.command());
        return known ? new Verbs.Ack(Verbs.ACK_DELIVERED, "")
                : new Verbs.Ack(Verbs.ACK_FAILED, "unknown command");
    }

    private Verbs.Ack message(Verbs.Message verb) {
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        player.sendMessage(SnText.color(verb.text()));
        return delivered();
    }

    private Verbs.Ack title(Verbs.Title verb) {
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        player.showTitle(Title.title(SnText.color(verb.title()), SnText.color(verb.subtitle()),
                Title.Times.times(ticks(verb.fadeInTicks()), ticks(verb.stayTicks()),
                        ticks(verb.fadeOutTicks()))));
        return delivered();
    }

    private Verbs.Ack actionbar(Verbs.Actionbar verb) {
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        player.sendActionBar(SnText.color(verb.text()));
        return delivered();
    }

    private Verbs.Ack sound(Verbs.Sound verb) {
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        if (!SoundUtil.resolves(verb.spec())) {
            return new Verbs.Ack(Verbs.ACK_FAILED, "unresolvable sound spec: " + verb.spec());
        }
        SoundUtil.play(player, verb.spec());
        return delivered();
    }

    /**
     * Bars are keyed PER PLAYER (prefix + player UUID + '/' + barId), so a shared barId
     * never evicts another player's bar. HIDE unregisters the entry so ids do not
     * accumulate; a HIDE for an offline player still frees the registration.
     */
    private Verbs.Ack bossbar(Verbs.Bossbar verb) {
        String barId = BAR_ID_PREFIX + verb.target() + "/" + verb.barId();
        if (verb.action() == Verbs.BAR_HIDE) {
            selfCtx.bossbars().remove(barId); // hides from all viewers, cancels timer, unregisters
            return delivered();
        }
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        switch (verb.action()) {
            case Verbs.BAR_SHOW -> {
                selfCtx.bossbars().create(barId)
                        .text(verb.text())
                        .progress(verb.progress())
                        .color(barColor(verb.color()))
                        .overlay(barOverlay(verb.overlay()))
                        .build();
                selfCtx.bossbars().show(player, barId);
            }
            case Verbs.BAR_UPDATE -> {
                if (!selfCtx.bossbars().exists(barId)) {
                    return new Verbs.Ack(Verbs.ACK_FAILED,
                            "unknown bar '" + verb.barId() + "' on this backend (show it first)");
                }
                if (!verb.text().isEmpty()) {
                    selfCtx.bossbars().setText(barId, verb.text());
                }
                selfCtx.bossbars().setProgress(barId, verb.progress());
            }
            default -> {
                return new Verbs.Ack(Verbs.ACK_FAILED, "unknown bossbar action " + verb.action());
            }
        }
        return delivered();
    }

    private Verbs.Ack actions(Verbs.Actions verb) {
        if (!rateAllows()) {
            return new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST,
                    "actions rate limit exceeded (" + ratePerSecond + "/s)");
        }
        Player player = online(verb.target());
        if (player == null) {
            return offline();
        }
        // Fail-closed gate: run nothing unless EVERY line's effective tag is safe, so the
        // console/op tags of the ActionEngine can never be reached over the bridge
        List<String> tags = selfCtx.actions().effectiveTags(verb.actions());
        for (String tag : tags) {
            if (SAFE_ACTION_TAGS.contains(tag)) {
                continue;
            }
            if (selfCtx.actions().hasHandler(tag)) {
                return new Verbs.Ack(Verbs.ACK_DENIED_BY_ALLOWLIST,
                        "action tag not permitted over the bridge: [" + tag + "]");
            }
            return new Verbs.Ack(Verbs.ACK_UNSUPPORTED_VERB, "unknown action tag: [" + tag + "]");
        }
        selfCtx.actions().run(player, verb.actions());
        return delivered();
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private boolean rateAllows() {
        long now = System.nanoTime() / 1_000_000L;
        if (now - rateWindowStart >= 1_000L) {
            rateWindowStart = now;
            rateUsed = 0;
        }
        return ++rateUsed <= ratePerSecond;
    }

    private static @Nullable Player online(UUID target) {
        Player player = Bukkit.getPlayer(target);
        return player != null && player.isOnline() ? player : null;
    }

    private static Verbs.Ack delivered() {
        return new Verbs.Ack(Verbs.ACK_DELIVERED, "");
    }

    private static Verbs.Ack offline() {
        return new Verbs.Ack(Verbs.ACK_FAILED, "target player offline on this backend");
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }

    private static BossBar.@Nullable Color barColor(String name) {
        if (name.isEmpty()) {
            return null; // builder keeps its default
        }
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BossBar.@Nullable Overlay barOverlay(String name) {
        if (name.isEmpty()) {
            return null;
        }
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
