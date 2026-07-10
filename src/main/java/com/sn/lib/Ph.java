package com.sn.lib;

/**
 * Local placeholder pair resolved by the text pipeline before PlaceholderAPI.
 *
 * @param key   placeholder name without delimiters (matched as {@code %key%} and {@code {key}})
 * @param value replacement text
 */
public record Ph(String key, String value) {

    /** Creates a pair from any value via {@link String#valueOf(Object)}. */
    public static Ph of(String key, Object value) {
        return new Ph(key, String.valueOf(value));
    }
}
