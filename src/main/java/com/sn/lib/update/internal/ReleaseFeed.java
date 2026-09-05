package com.sn.lib.update.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;

/**
 * Paginated shared read of a GitHub releases LIST feed: the multi-plugin releases repo a
 * consumer polls through {@code sn.updates(owner/repo, tagPrefix)}.
 *
 * <p>The GitHub list endpoint caps a page at 100 releases, so a single request only ever
 * sees the 100 newest releases of the WHOLE repo. In a repo shared by dozens of plugins
 * that window stops covering every plugin as soon as the repo passes 100 releases: the
 * plugins whose newest release fell out of the window simply find no tag of theirs and
 * report a missing prefix, and which plugins those are drifts with every release anyone
 * publishes. This class walks the pages instead, up to {@link #MAX_PAGES}.</p>
 *
 * <p>Walking pages multiplies requests, and the GitHub API allows 60 unauthenticated
 * requests per hour per IP - a server running 30 consumers would exhaust that on a single
 * boot. So a page is fetched ONCE for the whole server and shared: every consumer of the
 * same repo reads the same cached pages ({@value #TTL_MINUTES}-minute TTL, which spans a
 * boot's worth of staggered first checks and each 6-hour cycle), and concurrent readers of
 * a page not yet cached wait on the one in-flight request instead of firing their own. Cost
 * per cycle is therefore one request per page of the repo, no matter how many plugins are
 * installed. Only the parsed {@code (tag, url)} pairs are retained, never the raw bodies,
 * which run into hundreds of KB per page.</p>
 *
 * <p>Server-wide statics justified: a releases feed is content-addressed and identical for
 * every consumer reading the same repo. {@link #clearCache()} is invoked by the SnLib
 * plugin teardown. A failed fetch is never cached, so the next check retries it.</p>
 */
public final class ReleaseFeed {

    /** Releases per API page; 100 is the GitHub maximum. */
    private static final int PER_PAGE = 100;

    /** Hard ceiling of pages walked per scan: the newest {@code PER_PAGE * MAX_PAGES} releases. */
    private static final int MAX_PAGES = 10;

    private static final int TTL_MINUTES = 5;
    private static final long TTL_NANOS = Duration.ofMinutes(TTL_MINUTES).toNanos();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Parsed pages keyed by repo + page + token identity. */
    private static final Map<String, CachedPage> CACHE = new ConcurrentHashMap<>();

    /** In-flight dedupe: one HTTP request per page no matter how many concurrent readers. */
    private static final Map<String, CompletableFuture<List<ReleaseTag>>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private static final Object CLIENT_LOCK = new Object();
    private static volatile @Nullable HttpClient client;

    private ReleaseFeed() {
    }

    /**
     * Every release of {@code repo} whose tag starts with {@code tagPrefix}, newest first,
     * read across as many pages as the repo has (bounded by {@link #MAX_PAGES}). The walk
     * ends at the first short page - the last one - so a repo smaller than a page still
     * costs a single request. An empty {@link Scan#matches()} means the prefix is absent
     * from everything scanned; {@link Scan#truncated()} tells the caller whether that
     * covered the whole repo or stopped at the ceiling.
     */
    public static Scan matching(String repo, String tagPrefix, @Nullable String token)
            throws FeedException, InterruptedException {
        return matching(tagPrefix, page -> page(repo, page, token));
    }

    /** Page-walking half of {@link #matching(String, String, String)}, reader injected. */
    static Scan matching(String tagPrefix, PageReader reader)
            throws FeedException, InterruptedException {
        List<ReleaseTag> matches = new ArrayList<>();
        int scanned = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<ReleaseTag> entries = reader.read(page);
            scanned += entries.size();
            for (ReleaseTag entry : entries) {
                if (entry.tag().startsWith(tagPrefix)) {
                    matches.add(entry);
                }
            }
            if (entries.size() < PER_PAGE) {
                return new Scan(List.copyOf(matches), false, scanned);
            }
        }
        return new Scan(List.copyOf(matches), true, scanned);
    }

    /** Empties the page cache and releases the HTTP client; called by the SnLib teardown. */
    public static void clearCache() {
        CACHE.clear();
        IN_FLIGHT.clear();
        HttpClient current;
        synchronized (CLIENT_LOCK) {
            current = client;
            client = null;
        }
        if (current != null) {
            current.shutdown();
        }
    }

    /**
     * One page of the feed, served from the cache when fresh. A miss claims the fetch by
     * publishing its future first: whoever loses that race waits on the winner's request
     * rather than issuing a second one. Neither the cache nor the futures outlive a
     * failure - the entry is only stored once the body parsed.
     */
    private static List<ReleaseTag> page(String repo, int page, @Nullable String token)
            throws FeedException, InterruptedException {
        String key = repo + '\n' + page + '\n' + tokenId(token);
        CachedPage cached = CACHE.get(key);
        if (cached != null && System.nanoTime() - cached.stamp() < TTL_NANOS) {
            return cached.entries();
        }
        CompletableFuture<List<ReleaseTag>> claim = new CompletableFuture<>();
        CompletableFuture<List<ReleaseTag>> running = IN_FLIGHT.putIfAbsent(key, claim);
        if (running != null) {
            return await(running);
        }
        try {
            List<ReleaseTag> entries = fetch(repo, page, token);
            CACHE.put(key, new CachedPage(System.nanoTime(), entries));
            claim.complete(entries);
            return entries;
        } catch (FeedException | InterruptedException | RuntimeException e) {
            claim.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(key, claim);
        }
    }

    /**
     * Result of the request another thread is already running. Its failure is re-raised
     * here as this thread's own; an interruption of the OWNING thread surfaces as a plain
     * feed failure, since this thread was never interrupted.
     */
    private static List<ReleaseTag> await(CompletableFuture<List<ReleaseTag>> running)
            throws FeedException, InterruptedException {
        try {
            return running.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FeedException feed) {
                throw feed;
            }
            if (cause instanceof InterruptedException) {
                throw new FeedException("interrupted");
            }
            throw new FeedException(String.valueOf(cause));
        }
    }

    /** One page off the network, parsed into immutable {@code (tag, url)} pairs. */
    private static List<ReleaseTag> fetch(String repo, int page, @Nullable String token)
            throws FeedException, InterruptedException {
        String endpoint = "https://api.github.com/repos/" + repo
                + "/releases?per_page=" + PER_PAGE + "&page=" + page;
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SnLib-UpdateChecker");
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response =
                    client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new FeedException("HTTP " + response.statusCode());
            }
            return List.copyOf(parseReleaseTags(response.body()));
        } catch (IOException e) {
            throw new FeedException(String.valueOf(e));
        }
    }

    private static HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (CLIENT_LOCK) {
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
     * Identity of the token inside a cache key: its hash, so no raw secret is held in a
     * long-lived static map. Two consumers reading the same repo with the same token share
     * a page; one reading it with a different token does not.
     */
    private static String tokenId(@Nullable String token) {
        return token == null || token.isEmpty() ? "-" : Integer.toHexString(token.hashCode());
    }

    /**
     * Scans a GitHub {@code releases} array (as returned by the list endpoint) for every
     * {@code tag_name}, pairing each with the nearest preceding {@code html_url}. This
     * pairing is safe because each release object emits its own {@code html_url} once,
     * before its {@code tag_name}, and asset entries never carry an {@code html_url} key
     * (only a plain {@code url}), so no other release's field can land in between. Same
     * hand-scanned-JSON caveat as {@code UpdateChecker.jsonString}: a release body
     * containing the literal text {@code "tag_name"} would confuse this scan.
     */
    static List<ReleaseTag> parseReleaseTags(@Nullable String body) {
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

    /**
     * String value of the {@code field} occurrence starting at {@code at}, scanned by hand
     * without a JSON library (technique of {@code UpdateChecker.jsonString}, duplicated
     * following the self-contained-utils precedent): skips spaces and the colon, requires
     * an opening quote and reads to the closing quote unescaping {@code \"}, {@code \\}
     * and {@code \/} (other escapes are kept verbatim). Any unexpected shape returns null.
     */
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

    /** Reader of one page of the feed, injected so the page walk is testable without HTTP. */
    interface PageReader {

        List<ReleaseTag> read(int page) throws FeedException, InterruptedException;
    }

    /**
     * Outcome of a prefix scan: the matching releases newest first, whether the walk hit
     * the page ceiling instead of the end of the repo, and how many releases it read.
     */
    public record Scan(List<ReleaseTag> matches, boolean truncated, int scanned) {
    }

    /** One {@code (tag_name, html_url)} pair scanned out of a releases list entry. */
    public record ReleaseTag(String tag, String url) {
    }

    /** One cached page and the nanoTime it was stored at. */
    private record CachedPage(long stamp, List<ReleaseTag> entries) {
    }

    /**
     * A feed read that did not produce a page. Its message is the tail the caller appends
     * to its own WARN, so it reads exactly as the failure did before this class existed.
     */
    public static final class FeedException extends Exception {

        private static final long serialVersionUID = 1L;

        FeedException(String message) {
            super(message);
        }
    }
}
