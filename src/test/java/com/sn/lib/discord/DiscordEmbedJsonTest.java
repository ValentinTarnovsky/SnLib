package com.sn.lib.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * JSON of {@link DiscordWebhook.Embed}: the 1.41.0 fields (title url, author bar, thumbnail,
 * image, footer icon) render in Discord's shape, and blank values leave no key behind.
 */
class DiscordEmbedJsonTest {

    private static String json(DiscordWebhook.Embed embed) {
        StringBuilder out = new StringBuilder();
        embed.appendJson(out);
        return out.toString();
    }

    @Test
    void rendersEveryField() {
        DiscordWebhook.Embed embed = new DiscordWebhook(null).embed()
                .title("T")
                .url("https://t")
                .description("D")
                .author("A", "https://a", "https://ai")
                .thumbnail("https://th")
                .image("https://im")
                .footer("F", "https://fi");
        assertEquals("{\"title\":\"T\",\"url\":\"https://t\",\"description\":\"D\","
                + "\"footer\":{\"text\":\"F\",\"icon_url\":\"https://fi\"},"
                + "\"author\":{\"name\":\"A\",\"url\":\"https://a\",\"icon_url\":\"https://ai\"},"
                + "\"thumbnail\":{\"url\":\"https://th\"},\"image\":{\"url\":\"https://im\"}}", json(embed));
    }

    @Test
    void blankValuesAreOmitted() {
        DiscordWebhook.Embed embed = new DiscordWebhook(null).embed()
                .title("T")
                .url(" ")
                .author("", "https://a", "https://ai")
                .thumbnail(null)
                .image("")
                .footer("F", "");
        assertEquals("{\"title\":\"T\",\"footer\":{\"text\":\"F\"}}", json(embed));
    }

    @Test
    void authorWithoutLinks() {
        DiscordWebhook.Embed embed = new DiscordWebhook(null).embed().author("A", null, " ");
        assertEquals("{\"author\":{\"name\":\"A\"}}", json(embed));
    }
}
