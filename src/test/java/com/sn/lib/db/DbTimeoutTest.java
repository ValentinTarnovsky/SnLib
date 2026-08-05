package com.sn.lib.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDBC connect/read bounds: the clamps of the two config keys and the exact MySQL URL
 * they render into. The URL is one string concatenation shared by every MySQL consumer,
 * so a stray separator breaks all of them at once and nothing else would catch it.
 */
class DbTimeoutTest {

    @Test
    void connectTimeoutNeverFallsToZero() {
        assertEquals(1, DbConfig.clampConnectTimeout(0));
        assertEquals(1, DbConfig.clampConnectTimeout(-30));
        assertEquals(10, DbConfig.clampConnectTimeout(10));
        assertEquals(DbConfig.MAX_TIMEOUT_SECONDS,
                DbConfig.clampConnectTimeout(DbConfig.MAX_TIMEOUT_SECONDS + 1));
    }

    @Test
    void socketTimeoutKeepsZeroAsUnlimited() {
        assertEquals(0, DbConfig.clampSocketTimeout(0));
        assertEquals(0, DbConfig.clampSocketTimeout(-1));
        assertEquals(30, DbConfig.clampSocketTimeout(30));
        assertEquals(DbConfig.MAX_TIMEOUT_SECONDS,
                DbConfig.clampSocketTimeout(Integer.MAX_VALUE));
    }

    @Test
    void mysqlUrlKeepsTheLegacyParametersAndAddsTheBounds() {
        String url = SnDb.mysqlUrl("db.host", 3306, "sngens", false, 10_000L, 30_000L);
        assertEquals("jdbc:mysql://db.host:3306/sngens?useSSL=false"
                + "&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&connectTimeout=10000&socketTimeout=30000", url);
    }

    @Test
    void mysqlUrlCarriesSslAndAnUnlimitedSocketTimeout() {
        String url = SnDb.mysqlUrl("127.0.0.1", 3307, "data", true, 5_000L, 0L);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3307/data?useSSL=true"), url);
        assertTrue(url.endsWith("&connectTimeout=5000&socketTimeout=0"), url);
        assertEquals(1, url.chars().filter(c -> c == '?').count(), url);
    }
}
