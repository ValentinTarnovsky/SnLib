package com.sn.lib.db;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sn.lib.Sn;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Dual SQLite/MySQL database module of one consumer context, pooled through HikariCP.
 *
 * <p>Threading contract: JDBC never touches the main thread. Every operation runs on a
 * dedicated daemon executor named {@code <plugin>-db} whose threads pin their context
 * classloader to the owning consumer plugin, and results come back as {@link SnFuture}
 * (main-thread consumption via {@code thenSync}). The pool itself is created lazily on
 * that executor, so constructing this module never opens a connection.</p>
 *
 * <p>Backend profiles: SQLite always runs with {@code maximumPoolSize=1} plus
 * {@code busy_timeout=5000} and {@code journal_mode=WAL} applied on the first connect;
 * MySQL defaults to a pool of 4. HikariCP ships relocated to
 * {@code com.sn.lib.libs.hikari} (the shade rewrites these imports); the SQLite and
 * MySQL drivers ship unrelocated as the single server-wide copy.</p>
 *
 * <p>{@link #shutdown()} rejects new work, drains pending writes for up to 10 seconds,
 * then interrupts stragglers with {@code shutdownNow()} and unpins the worker context
 * classloaders so a hung query cannot retain the consumer classloader; the pool closes
 * last.</p>
 */
public final class SnDb {

    /** SQL callback that consumes a JDBC object. */
    public interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }

    /** SQL callback that maps a JDBC object to a result. */
    public interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }

    private static final long SHUTDOWN_JOIN_SECONDS = 10L;

    private final Sn ctx;
    private final DbConfig config;
    private final String poolName;
    private final ExecutorService executor;
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object dataSourceLock = new Object();

    private volatile HikariDataSource dataSource;
    private volatile boolean bootstrapping;

    public SnDb(Sn ctx, DbConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.poolName = ctx.plugin().getName() + "-db";
        int threads = config.type() == DbConfig.Type.SQLITE ? 1 : Math.max(1, config.poolSize());
        AtomicInteger sequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(SnDb.class.getClassLoader());
                }
            }, threads == 1 ? poolName : poolName + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(ctx.plugin().getClass().getClassLoader());
            workers.add(thread);
            return thread;
        });
    }

    /** Parsed connection settings this module runs with. */
    public DbConfig config() {
        return config;
    }

    /**
     * Creates every table asynchronously ({@code CREATE TABLE IF NOT EXISTS} per schema).
     * The standard enable-time call is {@code bootstrap(...).orDisablePlugin()}; while
     * the returned future is pending, main-thread joins are considered bootstrap phase.
     */
    public SnFuture<Void> bootstrap(Schema... schemas) {
        bootstrapping = true;
        SnFuture<Void> future = submit(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (Schema schema : schemas) {
                    statement.executeUpdate(schema.createSql());
                }
            }
            return null;
        });
        future.delegate.whenComplete((value, error) -> bootstrapping = false);
        return future;
    }

    /** Runs a prepared query off the main thread and maps its result set. */
    public <R> SnFuture<R> query(String sql, SqlConsumer<PreparedStatement> binder,
            SqlFunction<ResultSet, R> mapper) {
        return submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.accept(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return mapper.apply(resultSet);
                }
            }
        });
    }

    /** Runs a prepared update off the main thread; the value is the affected row count. */
    public SnFuture<Integer> update(String sql, SqlConsumer<PreparedStatement> binder) {
        return submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.accept(statement);
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Runs the work inside one transaction off the main thread: commit on success,
     * rollback on any failure.
     */
    public SnFuture<Void> transaction(SqlConsumer<Connection> work) {
        return submit(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.accept(connection);
                connection.commit();
            } catch (SQLException | RuntimeException | Error e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
            return null;
        });
    }

    /**
     * Tears the module down: new operations are rejected, pending work is joined for up
     * to {@value #SHUTDOWN_JOIN_SECONDS} seconds, stragglers are interrupted with
     * {@code shutdownNow()} and their context classloader is reset so a hung query never
     * pins the consumer classloader, and finally the pool closes. Idempotent.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(SHUTDOWN_JOIN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            executor.shutdownNow();
            ctx.plugin().getLogger().warning("Pool " + poolName + " no termino en "
                    + SHUTDOWN_JOIN_SECONDS + "s; shutdownNow() forzado");
            for (Thread worker : workers) {
                worker.setContextClassLoader(SnDb.class.getClassLoader());
            }
        }
        synchronized (dataSourceLock) {
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
        }
    }

    /** True while an enable-time {@link #bootstrap} is still pending. */
    boolean inBootstrap() {
        return bootstrapping;
    }

    private <R> SnFuture<R> submit(SqlFunction<Connection, R> work) {
        CompletableFuture<R> result = new CompletableFuture<>();
        SnFuture<R> future = new SnFuture<>(ctx, this, result);
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("SnDb cerrado: " + poolName));
            return future;
        }
        try {
            executor.execute(() -> {
                try (Connection connection = dataSource().getConnection()) {
                    result.complete(work.apply(connection));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return future;
    }

    private HikariDataSource dataSource() {
        HikariDataSource current = dataSource;
        if (current != null) {
            return current;
        }
        synchronized (dataSourceLock) {
            if (dataSource == null) {
                if (closed.get()) {
                    throw new IllegalStateException("SnDb cerrado: " + poolName);
                }
                dataSource = createDataSource();
            }
            return dataSource;
        }
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        if (config.type() == DbConfig.Type.SQLITE) {
            File file = config.sqliteFile();
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setMaximumPoolSize(1);
            hikari.addDataSourceProperty("busy_timeout", "5000");
            hikari.addDataSourceProperty("journal_mode", "WAL");
        } else {
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + config.host() + ":" + config.port() + "/"
                    + config.database() + "?useSSL=" + config.ssl()
                    + "&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setMaximumPoolSize(Math.max(1, config.poolSize()));
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }
        return new HikariDataSource(hikari);
    }
}
