package com.sn.lib.db;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Dialect-aware single-row upsert built via {@link SnDb#upsert(String)}.
 *
 * <p>{@link #keys} declares the conflict-key columns and {@link #set} the updatable
 * columns; both are repeatable and every value binds positionally through
 * {@code setObject}. {@link #run()} renders the statement for the backend dialect:
 * SQLite uses {@code INSERT ... ON CONFLICT(keys) DO UPDATE SET col=excluded.col} and
 * MySQL uses {@code INSERT ... ON DUPLICATE KEY UPDATE col=VALUES(col)}. The SQLite form
 * requires a UNIQUE or PRIMARY KEY constraint over the key columns; the MySQL form
 * relies on the table's own unique indexes.</p>
 *
 * <p>Table and column names are code-side identifiers, never user input; they are
 * validated against {@code [A-Za-z_][A-Za-z0-9_]*} as a hard stop.</p>
 */
public final class UpsertBuilder {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final SnDb db;
    private final String table;
    private final List<String> keyColumns = new ArrayList<>();
    private final List<Object> keyValues = new ArrayList<>();
    private final List<String> setColumns = new ArrayList<>();
    private final List<Object> setValues = new ArrayList<>();

    UpsertBuilder(SnDb db, String table) {
        this.db = db;
        this.table = identifier(table);
    }

    /** Adds one conflict-key column with its value; repeatable. */
    public UpsertBuilder keys(String column, Object value) {
        keyColumns.add(identifier(column));
        keyValues.add(value);
        return this;
    }

    /** Adds one updatable column with its value; repeatable. */
    public UpsertBuilder set(String column, Object value) {
        setColumns.add(identifier(column));
        setValues.add(value);
        return this;
    }

    /**
     * Renders the dialect statement and runs it off the main thread; the value is the
     * affected row count.
     *
     * @throws IllegalStateException if no {@link #keys} column was declared
     */
    public SnFuture<Integer> run() {
        if (keyColumns.isEmpty()) {
            throw new IllegalStateException(
                    "upsert(" + table + ") without keys(): declare at least one key column");
        }
        String sql = db.config().type() == DbConfig.Type.SQLITE ? sqliteSql() : mysqlSql();
        List<Object> values = new ArrayList<>(keyValues.size() + setValues.size());
        values.addAll(keyValues);
        values.addAll(setValues);
        return db.update(sql, statement -> {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
        });
    }

    private String sqliteSql() {
        StringBuilder sql = new StringBuilder(insertInto());
        sql.append(" ON CONFLICT(").append(String.join(", ", keyColumns)).append(")");
        if (setColumns.isEmpty()) {
            return sql.append(" DO NOTHING").toString();
        }
        StringJoiner assignments = new StringJoiner(", ");
        for (String column : setColumns) {
            assignments.add(column + "=excluded." + column);
        }
        return sql.append(" DO UPDATE SET ").append(assignments).toString();
    }

    private String mysqlSql() {
        StringBuilder sql = new StringBuilder(insertInto());
        sql.append(" ON DUPLICATE KEY UPDATE ");
        StringJoiner assignments = new StringJoiner(", ");
        if (setColumns.isEmpty()) {
            // MySQL requires at least one assignment: no-op refresh of the first key.
            String key = keyColumns.get(0);
            assignments.add(key + "=VALUES(" + key + ")");
        } else {
            for (String column : setColumns) {
                assignments.add(column + "=VALUES(" + column + ")");
            }
        }
        return sql.append(assignments).toString();
    }

    private String insertInto() {
        List<String> columns = new ArrayList<>(keyColumns.size() + setColumns.size());
        columns.addAll(keyColumns);
        columns.addAll(setColumns);
        StringJoiner marks = new StringJoiner(", ");
        for (int i = 0; i < columns.size(); i++) {
            marks.add("?");
        }
        return "INSERT INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES (" + marks + ")";
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + name + "'");
        }
        return name;
    }
}
