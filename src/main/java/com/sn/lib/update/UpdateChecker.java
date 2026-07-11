package com.sn.lib.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
 * only outputs are a console INFO on detection and a chat notice to joining players
 * holding {@code <plugin>.admin.update}. It is fully opt-in: a consumer that never
 * declares {@code SnSpec.builder().updates("owner/repo")} nor calls {@link #watch} or
 * {@link #checkNow} generates zero traffic and zero state.</p>
 *
 * <p>Each watched repo is polled against the GitHub {@code releases/latest} endpoint
 * (first check 60 seconds after enable, then every 6 hours, always off the main thread).
 * An optional read-only token read from the consumer's main config under
 * {@code update-check.token} on EVERY check (so it can change without a restart) is sent
 * as a Bearer header, which makes PRIVATE repos work; the token is never logged. Failures
 * WARN once per repo per enable and then stay silent. Findings are keyed per owning
 * plugin in a tenant registry swept on owner disable.</p>
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
     * Starts the periodic notify-only check against {@code owner/repo}: first check 60
     * seconds after enable, then every 6 hours, always off the main thread. An invalid
     * format WARNs and does nothing; re-watching the same repo replaces (and cancels)
     * the previous timer. The watch lives for the enable: a consumer reload neither
     * re-arms nor duplicates it.
     */
    public void watch(String ownerRepo) {
        if (ownerRepo == null || !REPO_PATTERN.matcher(ownerRepo).matches()) {
            ctx.plugin().getLogger().warning("updates: repo invalido '" + ownerRepo
                    + "'; formato esperado owner/repo");
            return;
        }
        registerState();
        TaskHandle handle = ctx.scheduler()
                .timerAsync(INITIAL_DELAY_TICKS, PERIOD_TICKS, () -> check(ownerRepo));
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
        if (ownerRepo == null || !REPO_PATTERN.matcher(ownerRepo).matches()) {
            ctx.plugin().getLogger().warning("updates: repo invalido '" + ownerRepo
                    + "'; formato esperado owner/repo");
            return;
        }
        registerState();
        ctx.scheduler().async(() -> check(ownerRepo));
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

    /** One check against releases/latest; runs off-main, WARNs once per repo on failure. */
    private void check(String repo) {
        if (ctx.isShuttingDown()) {
            return;
        }
        String token = config == null ? "" : config.getString(TOKEN_KEY, "");
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
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
                warnOnce(repo, "update check de '" + repo + "' fallo: HTTP "
                        + response.statusCode());
                return;
            }
            body = response.body();
        } catch (IOException e) {
            warnOnce(repo, "update check de '" + repo + "' fallo: " + e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnOnce(repo, "update check de '" + repo + "' fallo: interrumpido");
            return;
        }
        String tag = jsonString(body, "tag_name");
        if (tag == null) {
            warnOnce(repo, "update check de '" + repo + "' fallo: respuesta sin tag_name");
            return;
        }
        String url = jsonString(body, "html_url");
        String latest = stripTagPrefix(tag);
        String current = ctx.plugin().getPluginMeta().getVersion();
        if (SemverComparator.compareVersions(latest, current) > 0) {
            Finding finding = new Finding(latest, current, url == null ? "" : url);
            Finding previous = state.findings.put(repo, finding);
            if (previous == null || !previous.latest().equals(latest)) {
                ctx.plugin().getLogger().info("Version " + latest + " disponible, instalada "
                        + current + ": " + finding.url());
            }
        } else {
            state.findings.remove(repo);
        }
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
                            player.sendMessage(SnText.color("&e" + owner.getName()
                                    + " &7tiene una version nueva: &a" + f.latest()
                                    + " &7(instalada &c" + f.current() + "&7) &f" + f.url()));
                        }
                    });
                }
            });
        }
    }
}
