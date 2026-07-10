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

    private final Type type;
    private final File sqliteFile;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final boolean ssl;

    private DbConfig(Type type, File sqliteFile, String host, int port, String database,
            String username, String password, int poolSize, boolean ssl) {
        this.type = type;
        this.sqliteFile = sqliteFile;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.ssl = ssl;
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
                plugin.getLogger().warning("[" + plugin.getName() + "] database.type invalido: '"
                        + rawType + "', usando sqlite");
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
        return new DbConfig(type, sqliteFile, host, port, database, username, password, poolSize, ssl);
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
}
