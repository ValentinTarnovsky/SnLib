package com.sn.lib.db;

import java.io.File;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Connection settings of a consumer database, parsed from the {@code database} section
 * of its main config.
 *
 * <p>Recognized keys: {@code type} (sqlite or mysql, default sqlite), {@code file}
 * (SQLite path, relative to the plugin data folder), and for MySQL {@code host},
 * {@code port}, {@code database}, {@code username}, {@code password}, {@code pool-size}
 * and {@code ssl}. A missing section or an unknown type falls back to SQLite at
 * {@code <dataFolder>/database.db}.</p>
 *
 * <p>Two optional keys bound how long the driver may block, so an unreachable host cannot
 * outlive a caller's own budget: {@code connect-timeout-seconds} (default
 * {@value #DEFAULT_CONNECT_TIMEOUT_SECONDS}, minimum 1) caps opening a connection, and
 * {@code socket-timeout-seconds} (default {@value #DEFAULT_SOCKET_TIMEOUT_SECONDS},
 * {@code 0} = unlimited) caps a read on an already-open one - the case that otherwise
 * hangs forever on a black-holed link. Both clamp to
 * {@value #MAX_TIMEOUT_SECONDS} seconds. Omitting them keeps every existing config
 * working; they are read from the same {@code database} section.</p>
 */
public final class DbConfig {

    /** Supported database backends. */
    public enum Type {
        SQLITE,
        MYSQL
    }

    private static final String DEFAULT_SQLITE_FILE = "database.db";
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final int DEFAULT_MYSQL_POOL_SIZE = 4;
    /** Default cap on opening a connection, in seconds. */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    /** Default cap on a read over an open connection, in seconds; 0 disables it. */
    static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 30;
    /** Upper clamp shared by both timeouts, in seconds. */
    static final int MAX_TIMEOUT_SECONDS = 3600;

    private final Type type;
    private final File sqliteFile;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final boolean ssl;
    private final int connectTimeoutSeconds;
    private final int socketTimeoutSeconds;

    private DbConfig(Type type, File sqliteFile, String host, int port, String database,
            String username, String password, int poolSize, boolean ssl,
            int connectTimeoutSeconds, int socketTimeoutSeconds) {
        this.type = type;
        this.sqliteFile = sqliteFile;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.ssl = ssl;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    /**
     * Parses the given {@code database} section; a null section yields the SQLite
     * defaults. An unknown {@code type} logs one WARN and falls back to SQLite.
     */
    public static DbConfig load(JavaPlugin plugin, @Nullable ConfigurationSection section) {
        String rawType = section == null ? "sqlite" : section.getString("type", "sqlite");
        Type type;
        if ("mysql".equalsIgnoreCase(rawType)) {
            type = Type.MYSQL;
        } else {
            if (!"sqlite".equalsIgnoreCase(rawType)) {
                plugin.getLogger().warning("[" + plugin.getName() + "] invalid database.type: '"
                        + rawType + "', using sqlite");
            }
            type = Type.SQLITE;
        }
        String fileName = section == null
                ? DEFAULT_SQLITE_FILE
                : section.getString("file", DEFAULT_SQLITE_FILE);
        File candidate = new File(fileName);
        File sqliteFile = candidate.isAbsolute() ? candidate : new File(plugin.getDataFolder(), fileName);
        String defaultDatabase = plugin.getName().toLowerCase(Locale.ROOT);
        String host = section == null ? "localhost" : section.getString("host", "localhost");
        int port = section == null ? DEFAULT_MYSQL_PORT : section.getInt("port", DEFAULT_MYSQL_PORT);
        String database = section == null
                ? defaultDatabase
                : section.getString("database", defaultDatabase);
        String username = section == null ? "root" : section.getString("username", "root");
        String password = section == null ? "" : section.getString("password", "");
        int poolSize = section == null
                ? DEFAULT_MYSQL_POOL_SIZE
                : Math.max(1, section.getInt("pool-size", DEFAULT_MYSQL_POOL_SIZE));
        boolean ssl = section != null && section.getBoolean("ssl", false);
        int connectTimeout = clampConnectTimeout(section == null
                ? DEFAULT_CONNECT_TIMEOUT_SECONDS
                : section.getInt("connect-timeout-seconds", DEFAULT_CONNECT_TIMEOUT_SECONDS));
        int socketTimeout = clampSocketTimeout(section == null
                ? DEFAULT_SOCKET_TIMEOUT_SECONDS
                : section.getInt("socket-timeout-seconds", DEFAULT_SOCKET_TIMEOUT_SECONDS));
        return new DbConfig(type, sqliteFile, host, port, database, username, password, poolSize,
                ssl, connectTimeout, socketTimeout);
    }

    /** Clamps the connect budget to 1..{@value #MAX_TIMEOUT_SECONDS}; it can never be zero. */
    static int clampConnectTimeout(int seconds) {
        return Math.min(MAX_TIMEOUT_SECONDS, Math.max(1, seconds));
    }

    /** Clamps the socket budget to 0..{@value #MAX_TIMEOUT_SECONDS}; 0 means unlimited. */
    static int clampSocketTimeout(int seconds) {
        return Math.min(MAX_TIMEOUT_SECONDS, Math.max(0, seconds));
    }

    /** Backend type. */
    public Type type() {
        return type;
    }

    /** Resolved SQLite database file; meaningful only when {@link #type()} is SQLITE. */
    public File sqliteFile() {
        return sqliteFile;
    }

    /** MySQL host. */
    public String host() {
        return host;
    }

    /** MySQL port. */
    public int port() {
        return port;
    }

    /** MySQL database name. */
    public String database() {
        return database;
    }

    /** MySQL username. */
    public String username() {
        return username;
    }

    /** MySQL password. */
    public String password() {
        return password;
    }

    /** MySQL connection pool size; SQLite is always pinned to a single connection. */
    public int poolSize() {
        return poolSize;
    }

    /** Whether the MySQL connection uses SSL. */
    public boolean ssl() {
        return ssl;
    }

    /**
     * Seconds a single connection attempt may take before it fails, from the
     * {@code connect-timeout-seconds} key (default
     * {@value #DEFAULT_CONNECT_TIMEOUT_SECONDS}, clamped to 1..{@value #MAX_TIMEOUT_SECONDS}).
     * Applied as HikariCP's {@code connectionTimeout} on both backends and as the MySQL
     * driver's {@code connectTimeout} on the URL.
     */
    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /**
     * Seconds a read over an already-open MySQL connection may block before it fails,
     * from the {@code socket-timeout-seconds} key (default
     * {@value #DEFAULT_SOCKET_TIMEOUT_SECONDS}, clamped to 0..{@value #MAX_TIMEOUT_SECONDS},
     * where {@code 0} means unlimited). Applied as the driver's {@code socketTimeout};
     * SQLite ignores it, being a local file.
     */
    public int socketTimeoutSeconds() {
        return socketTimeoutSeconds;
    }
}
