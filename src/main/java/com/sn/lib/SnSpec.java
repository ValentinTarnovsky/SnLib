package com.sn.lib;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable declaration of the SnLib modules a consumer plugin uses.
 *
 * <p>Part of the frozen entrypoint ({@code SnPlugin} + {@code requiredApiLevel()} +
 * {@code SnSpec} + {@link SnApi#LEVEL}): the builder surface below never changes within
 * a major version. A module accessor on {@link Sn} whose module was not declared here
 * throws {@link UnsupportedOperationException}.</p>
 */
public final class SnSpec {

    private final @Nullable String configName;
    private final boolean lang;
    private final boolean guis;
    private final @Nullable String itemsName;
    private final boolean db;
    private final boolean debugCommand;

    private SnSpec(Builder builder) {
        this.configName = builder.configName;
        this.lang = builder.lang;
        this.guis = builder.guis;
        this.itemsName = builder.itemsName;
        this.db = builder.db;
        this.debugCommand = builder.debugCommand;
    }

    /** Creates a new spec builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Managed main config file name, or null if the config module was not declared. */
    public @Nullable String config() {
        return configName;
    }

    /** Whether the lang module (messages_en.yml) was declared. */
    public boolean lang() {
        return lang;
    }

    /** Whether the guis module (guis/ folder) was declared. */
    public boolean guis() {
        return guis;
    }

    /** Items file name, or null if the items module was not declared with a YML source. */
    public @Nullable String items() {
        return itemsName;
    }

    /** Whether the database module was declared. */
    public boolean db() {
        return db;
    }

    /** Whether the runtime debug command was declared. */
    public boolean debugCommand() {
        return debugCommand;
    }

    /**
     * Builder for {@link SnSpec}. Every method is opt-in; omitted modules stay disabled.
     */
    public static final class Builder {

        private @Nullable String configName;
        private boolean lang;
        private boolean guis;
        private @Nullable String itemsName;
        private boolean db;
        private boolean debugCommand;

        private Builder() {
        }

        /** Declares the managed main config file (for example {@code "config.yml"}). */
        public Builder config(String fileName) {
            this.configName = fileName;
            return this;
        }

        /** Declares the lang module. */
        public Builder lang() {
            this.lang = true;
            return this;
        }

        /** Declares the guis module. */
        public Builder guis() {
            this.guis = true;
            return this;
        }

        /** Declares the items module backed by a YML file (for example {@code "items.yml"}). */
        public Builder items(String fileName) {
            this.itemsName = fileName;
            return this;
        }

        /** Declares the database module. */
        public Builder db() {
            this.db = true;
            return this;
        }

        /** Declares the runtime debug command. */
        public Builder debugCommand() {
            this.debugCommand = true;
            return this;
        }

        /** Builds the immutable spec. */
        public SnSpec build() {
            return new SnSpec(this);
        }
    }
}
