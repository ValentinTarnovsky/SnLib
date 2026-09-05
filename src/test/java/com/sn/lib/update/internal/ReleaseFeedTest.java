package com.sn.lib.update.internal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseFeedTest {

    @Test
    void parseReleaseTagsPairsEachTagWithItsOwnHtmlUrl() {
        // Field order matches the real GitHub API: html_url precedes tag_name, and
        // author (with its own nested html_url) and assets come AFTER tag_name.
        String body = "[{"
                + "\"url\":\"https://api.github.com/repos/o/r/releases/2\","
                + "\"html_url\":\"https://github.com/o/r/releases/tag/snclans-v1.4.0\","
                + "\"tag_name\":\"snclans-v1.4.0\","
                + "\"author\":{\"login\":\"o\",\"html_url\":\"https://github.com/o\"},"
                + "\"assets\":[{\"url\":\"https://api.github.com/repos/o/r/releases/assets/1\","
                + "\"browser_download_url\":\"https://github.com/o/r/releases/download/x/x.jar\"}]"
                + "},{"
                + "\"url\":\"https://api.github.com/repos/o/r/releases/1\","
                + "\"html_url\":\"https://github.com/o/r/releases/tag/snoki-v2.0.0\","
                + "\"tag_name\":\"snoki-v2.0.0\""
                + "}]";
        List<ReleaseFeed.ReleaseTag> tags = ReleaseFeed.parseReleaseTags(body);
        assertEquals(2, tags.size());
        assertEquals("snclans-v1.4.0", tags.get(0).tag());
        assertEquals("https://github.com/o/r/releases/tag/snclans-v1.4.0", tags.get(0).url());
        assertEquals("snoki-v2.0.0", tags.get(1).tag());
        assertEquals("https://github.com/o/r/releases/tag/snoki-v2.0.0", tags.get(1).url());
    }

    @Test
    void parseReleaseTagsHandlesEmptyList() {
        assertTrue(ReleaseFeed.parseReleaseTags("[]").isEmpty());
        assertTrue(ReleaseFeed.parseReleaseTags(null).isEmpty());
    }

    @Test
    void shortFirstPageCostsOneRequest() throws Exception {
        Reader reader = new Reader(List.of(page("snbans-v1.0.0", "snclans-v1.0.0")));
        ReleaseFeed.Scan scan = ReleaseFeed.matching("snbans-", reader);
        assertEquals(1, reader.pages.size());
        assertEquals(1, scan.matches().size());
        assertEquals("snbans-v1.0.0", scan.matches().get(0).tag());
        assertFalse(scan.truncated());
        assertEquals(2, scan.scanned());
    }

    @Test
    void walksPastAFullPageToReachAnOlderPrefix() throws Exception {
        // The regression this guards: a full first page used to be the whole search, so a
        // plugin whose newest release had fallen out of the 100 newest releases of a
        // SHARED repo reported no matching tag at all.
        Reader reader = new Reader(List.of(fullPage("snrecent-"), page("snbans-v1.8.2")));
        ReleaseFeed.Scan scan = ReleaseFeed.matching("snbans-", reader);
        assertEquals(List.of(1, 2), reader.pages);
        assertEquals(1, scan.matches().size());
        assertEquals("snbans-v1.8.2", scan.matches().get(0).tag());
        assertFalse(scan.truncated());
        assertEquals(101, scan.scanned());
    }

    @Test
    void keepsEveryMatchAcrossPagesNewestFirst() throws Exception {
        List<ReleaseFeed.ReleaseTag> first = fullPage("snother-");
        first.set(0, new ReleaseFeed.ReleaseTag("snbans-v1.8.2", "u1"));
        Reader reader = new Reader(List.of(first, page("snbans-v1.8.1", "snbans-v1.8.0")));
        ReleaseFeed.Scan scan = ReleaseFeed.matching("snbans-", reader);
        assertEquals(List.of("snbans-v1.8.2", "snbans-v1.8.1", "snbans-v1.8.0"),
                scan.matches().stream().map(ReleaseFeed.ReleaseTag::tag).toList());
    }

    @Test
    void stopsAtThePageCeilingAndSaysSo() throws Exception {
        List<List<ReleaseFeed.ReleaseTag>> pages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pages.add(fullPage("snother-"));
        }
        Reader reader = new Reader(pages);
        ReleaseFeed.Scan scan = ReleaseFeed.matching("snbans-", reader);
        assertEquals(10, reader.pages.size());
        assertTrue(scan.matches().isEmpty());
        assertTrue(scan.truncated());
        assertEquals(1000, scan.scanned());
    }

    @Test
    void anAbsentPrefixOnAShortFeedIsNotTruncated() throws Exception {
        Reader reader = new Reader(List.of(page("snclans-v1.0.0")));
        ReleaseFeed.Scan scan = ReleaseFeed.matching("snbans-", reader);
        assertTrue(scan.matches().isEmpty());
        assertFalse(scan.truncated());
    }

    private static List<ReleaseFeed.ReleaseTag> page(String... tags) {
        List<ReleaseFeed.ReleaseTag> out = new ArrayList<>();
        for (String tag : tags) {
            out.add(new ReleaseFeed.ReleaseTag(tag, "https://example.invalid/" + tag));
        }
        return out;
    }

    /** A page of exactly 100 entries: what the walk must not mistake for the last one. */
    private static List<ReleaseFeed.ReleaseTag> fullPage(String prefix) {
        List<ReleaseFeed.ReleaseTag> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            out.add(new ReleaseFeed.ReleaseTag(prefix + "v1.0." + i, "u" + i));
        }
        return out;
    }

    /** Page reader over canned pages, recording which pages the walk actually asked for. */
    private static final class Reader implements ReleaseFeed.PageReader {

        private final List<List<ReleaseFeed.ReleaseTag>> canned;
        private final List<Integer> pages = new ArrayList<>();

        Reader(List<List<ReleaseFeed.ReleaseTag>> canned) {
            this.canned = canned;
        }

        @Override
        public List<ReleaseFeed.ReleaseTag> read(int page) {
            pages.add(page);
            return page <= canned.size() ? canned.get(page - 1) : List.of();
        }
    }
}
