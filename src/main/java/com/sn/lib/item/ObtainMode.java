package com.sn.lib.item;

import java.util.Locale;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * How a registered item may legitimately enter circulation (spec field
 * {@code obtain-via}).
 */
public enum ObtainMode {

    /** No restriction; every acquisition path is allowed. Spec default ({@code ""}). */
    UNRESTRICTED,

    /**
     * Only via command or plugin API; other acquisition paths (crafting, mob pickup and
     * similar) are cancelled by the locked-item enforcement layer.
     */
    COMMAND_ONLY;

    /**
     * Lenient parse: null or blank yields {@link #UNRESTRICTED}; an unknown value sends
     * one warning to {@code warn} and also yields {@link #UNRESTRICTED}.
     */
    static ObtainMode parse(@Nullable String raw, @Nullable Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            return UNRESTRICTED;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(name);
        } catch (IllegalArgumentException unknown) {
            if (warn != null) {
                warn.accept("Invalid obtain-via '" + raw + "'; using UNRESTRICTED");
            }
            return UNRESTRICTED;
        }
    }
}
