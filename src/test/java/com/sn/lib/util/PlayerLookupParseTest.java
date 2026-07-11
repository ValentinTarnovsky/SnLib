package com.sn.lib.util;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLookupParseTest {

    @Test
    void parseUuidInsertsDashes() {
        String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                PlayerLookup.parseUuid(body));
    }

    @Test
    void parseUuidRejectsBadLengthOrNonHex() {
        assertNull(PlayerLookup.parseUuid("{\"id\":\"069a79f444e94726a5befca90e38aaf\"}"));
        assertNull(PlayerLookup.parseUuid("{\"id\":\"069a79f444e94726a5befca90e38aaf55\"}"));
        assertNull(PlayerLookup.parseUuid("{\"id\":\"g69a79f444e94726a5befca90e38aaf5\"}"));
    }

    @Test
    void parseUuidMissingFieldReturnsNull() {
        assertNull(PlayerLookup.parseUuid("{\"name\":\"Notch\"}"));
        assertNull(PlayerLookup.parseUuid("{\"id\":123}"));
        assertNull(PlayerLookup.parseUuid(null));
    }

    @Test
    void validNameAcceptsValidRejectsInvalid() {
        assertTrue(PlayerLookup.validName("Notch"));
        assertTrue(PlayerLookup.validName("a_1"));
        assertFalse(PlayerLookup.validName(null));
        assertFalse(PlayerLookup.validName(""));
        assertFalse(PlayerLookup.validName("seventeen_chars_x"));
        assertFalse(PlayerLookup.validName("bad-name"));
        assertFalse(PlayerLookup.validName("with space"));
    }
}
