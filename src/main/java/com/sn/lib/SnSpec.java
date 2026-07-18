package com.sn.lib;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable declaration of the SnLib modules a consumer plugin uses.
 *
 * <p>Part of the frozen entrypoint ({@code SnPlugin} + {@code requiredApiLevel()} +
 * {@code SnSpec} + {@link SnApi#LEVEL}): the builder surface below never changes within
 * a major version. A module accessor on {@link Sn} whose module was not declared here
 * throws {@link UnsupportedOperationException}.</p>
 *
 * <p>Everything declared is mounted by ONE init call when the consumer enables: the
 * managed config is seeded and merged (with the {@code update-configs} gate), lang and
 * the {@code guis/} folder load, the items file registers its definitions, the database
 * module comes up and the debug subcommand is injected. The matching teardown runs
 * automatically when the consumer disables.</p>
 */
public final class SnSpec {

    private final @Nullable String configName;
    private final boolean lang;
    private final boolean guis;
    private final @Nullable String itemsName;
    private final boolean db;
    private final boolean teleports;
    private final boolean debugCommand;
    private final @Nullable String updatesRepo;
    private final @Nullable String updatesTagPrefix;

    private SnSpec(Builder builder) {
        this.configName = builder.configName;
        this.lang = builder.lang;
        this.guis = builder.guis;
        this.itemsName = builder.itemsName;
        this.db = builder.db;
        this.teleports = builder.teleports;
        this.debugCommand = builder.debugCommand;
        this.updatesRepo = builder.updatesRepo;
        this.updatesTagPrefix = builder.updatesTagPrefix;
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

    /** Whether the warmup teleport module was declared. */
    public boolean teleports() {
        return teleports;
    }

    /** Whether the runtime debug command was declared. */
    public boolean debugCommand() {
        return debugCommand;
    }

    /** GitHub owner/repo of the update check, or null if it was not declared. */
    public @Nullable String updates() {
        return updatesRepo;
    }

    /**
     * Tag prefix for a shared multi-plugin releases repo, or null when the watched repo
     * hosts only this plugin's releases (the {@code releases/latest} endpoint applies).
     */
    public @Nullable String updatesTagPrefix() {
        return updatesTagPrefix;
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
        private boolean teleports;
        private boolean debugCommand;
        private @Nullable String updatesRepo;
        private @Nullable String updatesTagPrefix;

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

        /** Declares the warmup teleport module. */
        public Builder teleports() {
            this.teleports = true;
            return this;
        }

        /** Declares the runtime debug command. */
        public Builder debugCommand() {
            this.debugCommand = true;
            return this;
        }

        /**
         * Declares the notify-only update check against a GitHub repo dedicated to this
         * plugin's releases, format {@code owner/repo}; polls {@code releases/latest}.
         */
        public Builder updates(String ownerRepo) {
            this.updatesRepo = ownerRepo;
            this.updatesTagPrefix = null;
            return this;
        }

        /**
         * Declares the notify-only update check against a shared multi-plugin releases
         * repo: {@code ownerRepo} hosts tags for several plugins, and only tags starting
         * with {@code tagPrefix} (for example {@code "snclans-"} matching tags like
         * {@code snclans-v1.4.0}) are considered for this plugin. The highest matching
         * version wins; the prefix is stripped before comparison, then a leading
         * {@code v}/{@code V} is stripped the same way {@link #updates(String)} does.
         */
        public Builder updates(String ownerRepo, String tagPrefix) {
            this.updatesRepo = ownerRepo;
            this.updatesTagPrefix = tagPrefix;
            return this;
        }

        /** Builds the immutable spec. */
        public SnSpec build() {
            return new SnSpec(this);
        }
    }
}
