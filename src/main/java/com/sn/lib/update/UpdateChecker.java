package com.sn.lib.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.hook.SemverComparator;
import com.sn.lib.scheduler.TaskHandle;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.text.SnText;
import com.sn.lib.yml.SnYml;

/**
 * Notify-only update checker of a consumer context, reached through {@code sn.updates()}.
 *
 * <p>Hard rules of the module: it is STRICTLY notify-only - it never downloads any
 * artifact, never touches the running jar and never performs any kind of auto-swap; the
 * only outputs are a console INFO on detection and a chat notice to players holding
 * {@code <plugin>.admin.update} - those already online when a NEW finding lands and
 * those joining later. Neither output carries the release URL, only the versions.
 * It is fully opt-in: a consumer that never
 * declares {@code SnSpec.builder().updates("owner/repo")} nor calls {@link #watch} or
 * {@link #checkNow} generates zero traffic and zero state.</p>
 *
 * <p>Each watched repo is polled (first check 60 seconds after enable, then every 6 hours,
 * always off the main thread). A repo dedicated to one plugin is polled against the GitHub
 * {@code releases/latest} endpoint. A repo shared by several plugins is declared with a
 * tag prefix ({@link #watch(String, String)} / {@link #checkNow(String, String)}): the
 * {@code releases} list is polled instead, only tags starting with the prefix are
 * considered, and the highest matching version wins. An optional read-only token read from
 * the consumer's main config under {@code update-check.token} on EVERY check (so it can
 * change without a restart) is sent as a Bearer header, which makes PRIVATE repos work;
 * the token is never logged. Failures WARN once per repo per enable and then stay silent.
 * Findings are keyed per owning plugin in a tenant registry swept on owner disable.</p>
 */
public final class UpdateChecker {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long INITIAL_DELAY_TICKS = 1200L;
    private static final long PERIOD_TICKS = 432000L;
    private static final String TOKEN_KEY = "update-check.token";
    private static final long NOTIFY_DELAY_TICKS = 40L;
    private static final Pattern REPO_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    /**
     * Server-wide static justified: per-owner update state read by the shared join
     * listener; keyed by owning plugin and swept whole-key on owner disable.
     */
    private static final TenantRegistry<UpdateState> STATES = new TenantRegistry<>();

    private final Sn ctx;
    private final @Nullable SnYml config;
    private final Map<String, TaskHandle> watches = new ConcurrentHashMap<>();
    private final Set<String> warnedRepos = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stateRegistered = new AtomicBoolean();
    private final UpdateState state;

    private final Object clientLock = new Object();
    private volatile @Nullable HttpClient client;

    public UpdateChecker(Sn ctx, @Nullable SnYml config) {
        this.ctx = ctx;
        this.config = config;
        this.state = new UpdateState(ctx);
    }

    /**
     * Shared PlayerJoinEvent listener owned by SnLib that notifies pending findings of
     * every owner to joining players holding {@code <plugin>.admin.update}. Defined here
     * and inscribed in the ListenerHub; the registerEvents call happens uniquely in the
     * SnLibPlugin bootstrap. A consumer that wants the permission to default to op must
     * declare {@code <plugin>.admin.update} in ITS plugin.yml; without declaring it only
     * players granted the permission explicitly are notified.
     */
    public static Listener joinListener() {
        return new JoinListener();
    }

    /**
     * Starts the periodic notify-only check against {@code owner/repo}, a repo dedicated
     * to this plugin's releases (polls {@code releases/latest}): first check 60 seconds
     * after enable, then every 6 hours, always off the main thread. An invalid format
     * WARNs and does nothing; re-watching the same repo replaces (and cancels) the
     * previous timer. The watch lives for the enable: a consumer reload neither re-arms
     * nor duplicates it.
     */
    public void watch(String ownerRepo) {
        watch(ownerRepo, null);
    }

    /**
     * Starts the periodic notify-only check against a shared multi-plugin releases repo,
     * considering only tags starting with {@code tagPrefix} (pass {@code null} for a repo
     * dedicated to this plugin, equivalent to {@link #watch(String)}). Same timing and
     * validation as {@link #watch(String)}.
     */
    public void watch(String ownerRepo, @Nullable String tagPrefix) {
        if (ownerRepo == null || !REPO_PATTERN.matcher(ownerRepo).matches()) {
            ctx.plugin().getLogger().warning("updates: invalid repo '" + ownerRepo
                    + "'; expected format owner/repo");
            return;
        }
        registerState();
        TaskHandle handle = ctx.scheduler()
                .timerAsync(INITIAL_DELAY_TICKS, PERIOD_TICKS, () -> check(ownerRepo, tagPrefix));
        TaskHandle previous = watches.put(ownerRepo, handle);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * Runs ONE immediate notify-only check against {@code owner/repo} off the main
     * thread, without arming a timer: the explicit-call path for consumers that do not
     * declare the repo in their spec. An invalid format WARNs and does nothing.
     */
    public void checkNow(String ownerRepo) {
        checkNow(ownerRepo, null);
    }

    /**
     * Runs ONE immediate notify-only check against a shared multi-plugin releases repo,
     * considering only tags starting with {@code tagPrefix} (pass {@code null} for a repo
     * dedicated to this plugin, equivalent to {@link #checkNow(String)}). Arms no timer.
     */
    public void checkNow(String ownerRepo, @Nullable String tagPrefix) {
        if (ownerRepo == null || !REPO_PATTERN.matcher(ownerRepo).matches()) {
            ctx.plugin().getLogger().warning("updates: invalid repo '" + ownerRepo
                    + "'; expected format owner/repo");
            return;
        }
        registerState();
        ctx.scheduler().async(() -> check(ownerRepo, tagPrefix));
    }

    /**
     * Idempotent teardown invoked by the context shutdown: cancels every watch timer and
     * releases the HTTP client. The STATES entry of this owner is not touched here; the
     * generic tenant sweep of the teardown removes it.
     */
    public void shutdown() {
        for (TaskHandle handle : watches.values()) {
            handle.cancel();
        }
        watches.clear();
        HttpClient current = client;
        client = null;
        if (current != null) {
            current.shutdown();
        }
    }

    private void registerState() {
        if (stateRegistered.compareAndSet(false, true)) {
            STATES.add(ctx.plugin(), state);
        }
    }

    /**
     * One check against {@code repo}; runs off-main, WARNs once per repo on failure.
     * {@code tagPrefix == null} polls {@code releases/latest} (repo dedicated to this
     * plugin); otherwise polls the {@code releases} list and keeps only tags starting
     * with the prefix, picking the highest matching version.
     */
    private void check(String repo, @Nullable String tagPrefix) {
        if (ctx.isShuttingDown()) {
            return;
        }
        boolean shared = tagPrefix != null && !tagPrefix.isEmpty();
        String endpoint = shared
                ? "https://api.github.com/repos/" + repo + "/releases?per_page=100"
                : "https://api.github.com/repos/" + repo + "/releases/latest";
        String token = config == null ? "" : config.getString(TOKEN_KEY, "");
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SnLib-UpdateChecker");
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        String body;
        try {
            HttpResponse<String> response =
                    client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                warnOnce(repo, "update check of '" + repo + "' failed: HTTP "
                        + response.statusCode());
                return;
            }
            body = response.body();
        } catch (IOException e) {
            warnOnce(repo, "update check of '" + repo + "' failed: " + e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnOnce(repo, "update check of '" + repo + "' failed: interrupted");
            return;
        }
        String latest;
        String url;
        if (shared) {
            String bestTag = null;
            String bestUrl = "";
            for (ReleaseTag entry : parseReleaseTags(body)) {
                if (!entry.tag().startsWith(tagPrefix)) {
                    continue;
                }
                String candidate = stripTagPrefix(entry.tag().substring(tagPrefix.length()));
                if (bestTag == null || SemverComparator.compareVersions(candidate, bestTag) > 0) {
                    bestTag = candidate;
                    bestUrl = entry.url();
                }
            }
            if (bestTag == null) {
                warnOnce(repo, "update check of '" + repo + "' failed: no release tag matching prefix '"
                        + tagPrefix + "'");
                return;
            }
            latest = bestTag;
            url = bestUrl;
        } else {
            String tag = jsonString(body, "tag_name");
            if (tag == null) {
                warnOnce(repo, "update check of '" + repo + "' failed: response without tag_name");
                return;
            }
            latest = stripTagPrefix(tag);
            url = jsonString(body, "html_url");
        }
        String current = ctx.plugin().getPluginMeta().getVersion();
        if (SemverComparator.compareVersions(latest, current) > 0) {
            Finding finding = new Finding(latest, current, url == null ? "" : url);
            Finding previous = state.findings.put(repo, finding);
            if (previous == null || !previous.latest().equals(latest)) {
                ctx.plugin().getLogger().info("Version " + latest + " available, installed "
                        + current + ".");
                notifyOnline(finding);
            }
        } else {
            state.findings.remove(repo);
        }
    }

    /**
     * Chat notice of a NEW finding to admins already online at detection time (joining
     * players are covered by the shared join listener). The check runs off-main, so the
     * delivery hops to the main thread.
     */
    private void notifyOnline(Finding finding) {
        ctx.scheduler().sync(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(state.permission)) {
                    player.sendMessage(notice(ctx.plugin().getName(), finding));
                }
            }
        });
    }

    /**
     * Chat line of one finding; shared by the online notice and the join notice. No URL:
     * neither output of the module carries the release link, only the versions.
     */
    private static Component notice(String pluginName, Finding f) {
        return SnText.color("&e" + pluginName + " &7has a new version: &a" + f.latest()
                + " &7(installed &c" + f.current() + "&7)");
    }

    /** One WARN per repo per enable; later failures of the repo stay silent. */
    private void warnOnce(String repo, String message) {
        if (warnedRepos.add(repo)) {
            ctx.plugin().getLogger().warning(message);
        }
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (clientLock) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * First string value of {@code "field"} in a JSON body, scanned by hand without a
     * JSON library: finds the first quoted occurrence of the field name, skips spaces and
     * the colon, requires an opening quote and reads to the closing quote unescaping
     * {@code \"}, {@code \\} and {@code \/} (other escapes are kept verbatim). Any
     * unexpected shape returns null. Documented assumption: in the releases/latest
     * payload the first {@code html_url} occurrence is the release's own (it precedes
     * {@code author} and {@code assets}).
     */
    static @Nullable String jsonString(String body, String field) {
        if (body == null || field == null) {
            return null;
        }
        int at = body.indexOf('"' + field + '"');
        if (at < 0) {
            return null;
        }
        return stringValueAt(body, at, field);
    }

    /**
     * Scans a GitHub {@code releases} array (as returned by the list endpoint) for every
     * {@code tag_name}, pairing each with the nearest preceding {@code html_url}. This
     * pairing is safe because each release object emits its own {@code html_url} once,
     * before its {@code tag_name}, and asset entries never carry an {@code html_url} key
     * (only a plain {@code url}), so no other release's field can land in between.
     * Same hand-scanned-JSON caveats as {@link #jsonString}: a release body containing
     * the literal text {@code "tag_name"} would confuse this scan.
     */
    static List<ReleaseTag> parseReleaseTags(String body) {
        List<ReleaseTag> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        int searchFrom = 0;
        while (true) {
            int tagAt = body.indexOf("\"tag_name\"", searchFrom);
            if (tagAt < 0) {
                break;
            }
            searchFrom = tagAt + "\"tag_name\"".length();
            String tag = stringValueAt(body, tagAt, "tag_name");
            if (tag == null) {
                continue;
            }
            int htmlAt = body.lastIndexOf("\"html_url\"", tagAt);
            String url = htmlAt < 0 ? "" : stringValueAt(body, htmlAt, "html_url");
            out.add(new ReleaseTag(tag, url == null ? "" : url));
        }
        return out;
    }

    /** One {@code (tag_name, html_url)} pair scanned out of a releases list entry. */
    record ReleaseTag(String tag, String url) {
    }

    private static @Nullable String stringValueAt(String body, int at, String field) {
        int i = at + field.length() + 2;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != ':') {
            return null;
        }
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder(32);
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (i + 1 >= body.length()) {
                    return null;
                }
                char next = body.charAt(i + 1);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(c).append(next);
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return null;
    }

    /**
     * Trims the tag and strips a leading {@code v}/{@code V} ONLY when a digit follows:
     * {@code v1.4.0} becomes {@code 1.4.0} while {@code vanilla} stays intact.
     */
    static String stripTagPrefix(String tag) {
        String trimmed = tag.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && trimmed.charAt(1) >= '0' && trimmed.charAt(1) <= '9') {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    /** Per-owner findings read by the shared join listener. */
    private static final class UpdateState {

        final Sn ctx;
        final String permission;
        final Map<String, Finding> findings = new ConcurrentHashMap<>();

        UpdateState(Sn ctx) {
            this.ctx = ctx;
            this.permission = ctx.plugin().getName().toLowerCase(Locale.ROOT) + ".admin.update";
        }
    }

    /** One detected update: latest published version, installed version and release URL. */
    private record Finding(String latest, String current, String url) {
    }

    private static final class JoinListener implements Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            STATES.forEachOwner((owner, states) -> {
                for (UpdateState state : states) {
                    if (state.findings.isEmpty() || !player.hasPermission(state.permission)) {
                        continue;
                    }
                    state.ctx.scheduler().syncLater(NOTIFY_DELAY_TICKS, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        for (Finding f : state.findings.values()) {
                            player.sendMessage(notice(owner.getName(), f));
                        }
                    });
                }
            });
        }
    }
}
