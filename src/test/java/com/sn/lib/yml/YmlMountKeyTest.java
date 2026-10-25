package com.sn.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link YmlManager#normalize(String)}, the one mount key that both the mounts
 * and {@link YmlManager#unmount(String)} go through, so a path unmounts under any spelling it
 * was mounted with.
 */
class YmlMountKeyTest {

    @Test
    void backslashesAndLeadingSlashesFoldToOneKey() {
        assertEquals("modules/arena/arena.yml", YmlManager.normalize("modules/arena/arena.yml"));
        assertEquals("modules/arena/arena.yml", YmlManager.normalize("modules\\arena\\arena.yml"));
        assertEquals("modules/arena/arena.yml", YmlManager.normalize("/modules/arena/arena.yml"));
        assertEquals("modules/arena/arena.yml", YmlManager.normalize("//modules\\arena/arena.yml"));
    }

    @Test
    void aPlainNameStaysAsIs() {
        assertEquals("rewards.yml", YmlManager.normalize("rewards.yml"));
    }
}
