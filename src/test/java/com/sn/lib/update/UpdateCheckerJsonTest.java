package com.sn.lib.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateCheckerJsonTest {

    @Test
    void jsonStringExtractsTagName() {
        String body = "{\"url\":\"https://api.github.com/repos/o/r/releases/1\","
                + "\"tag_name\": \"v1.4.0\",\"name\":\"Release v1.4.0\"}";
        assertEquals("v1.4.0", UpdateChecker.jsonString(body, "tag_name"));
    }

    @Test
    void jsonStringFirstHtmlUrlWins() {
        String body = "{\"html_url\":\"https://github.com/o/r/releases/tag/v1.4.0\","
                + "\"author\":{\"login\":\"o\",\"html_url\":\"https://github.com/o\"}}";
        assertEquals("https://github.com/o/r/releases/tag/v1.4.0",
                UpdateChecker.jsonString(body, "html_url"));
    }

    @Test
    void jsonStringHandlesEscapedQuotes() {
        String body = "{\"body\":\"say \\\"hi\\\" and a back\\\\slash and a \\/slash\"}";
        assertEquals("say \"hi\" and a back\\slash and a /slash",
                UpdateChecker.jsonString(body, "body"));
    }

    @Test
    void jsonStringMissingFieldReturnsNull() {
        assertNull(UpdateChecker.jsonString("{\"name\":\"x\"}", "tag_name"));
        assertNull(UpdateChecker.jsonString("{\"tag_name\":123}", "tag_name"));
        assertNull(UpdateChecker.jsonString("{\"tag_name\":\"unterminated", "tag_name"));
    }

    @Test
    void stripTagPrefixStripsVOnlyBeforeDigit() {
        assertEquals("1.2.3", UpdateChecker.stripTagPrefix("v1.2.3"));
        assertEquals("2.0", UpdateChecker.stripTagPrefix("V2.0"));
        assertEquals("1.2.3", UpdateChecker.stripTagPrefix("1.2.3"));
        assertEquals("vanilla", UpdateChecker.stripTagPrefix("vanilla"));
    }
}
