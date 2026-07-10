package com.sn.lib.db;

/**
 * Declarative table definition consumed by {@link SnDb#bootstrap}: each schema renders
 * to one idempotent {@code CREATE TABLE IF NOT EXISTS} statement.
 */
public final class Schema {

    private final String table;
    private final String createSql;

    private Schema(String table, String createSql) {
        this.table = table;
        this.createSql = createSql;
    }

    /**
     * Schema from a table name plus its column definitions, for example
     * {@code Schema.of("players", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL")}.
     */
    public static Schema of(String table, String... columnDefs) {
        return new Schema(table,
                "CREATE TABLE IF NOT EXISTS " + table + " (" + String.join(", ", columnDefs) + ")");
    }

    /**
     * Schema from a raw statement for dialect-specific definitions; the SQL itself must
     * stay idempotent ({@code CREATE TABLE IF NOT EXISTS ...}).
     */
    public static Schema raw(String table, String createSql) {
        return new Schema(table, createSql);
    }

    /** Table name. */
    public String table() {
        return table;
    }

    /** Statement executed by {@link SnDb#bootstrap}. */
    public String createSql() {
        return createSql;
    }
}
