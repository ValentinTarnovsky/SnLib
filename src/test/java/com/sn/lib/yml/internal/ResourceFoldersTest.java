package com.sn.lib.yml.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the folder validation behind {@code GuiManager.loadFolder} and
 * {@code SnLang.addSource}: separators, dot segments, climbing out of the data folder,
 * absolute forms and the module's reserved folder.
 */
class ResourceFoldersTest {

    @Test
    void separatorsAndDotSegmentsNormalize() {
        assertEquals("modules/party/guis",
                ResourceFolders.normalize("modules/party/guis", "guis", "Menu"));
        assertEquals("modules/party/guis",
                ResourceFolders.normalize(" \\modules\\party\\guis\\ ", "guis", "Menu"));
        assertEquals("modules/party/guis",
                ResourceFolders.normalize("./modules//party/./guis/", "guis", "Menu"));
        assertEquals("modules/party/guis",
                ResourceFolders.normalize("modules/other/../party/guis", "guis", "Menu"));
    }

    @Test
    void leadingSlashStaysRelativeToTheDataFolder() {
        assertEquals("modules/party/guis",
                ResourceFolders.normalize("/modules/party/guis", "guis", "Menu"));
    }

    @Test
    void blankAndNullAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize(null, "guis", "Menu"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("  ", "guis", "Menu"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("./.", "guis", "Menu"));
    }

    @Test
    void climbingOutOfTheDataFolderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("../Other/guis", "guis", "Menu"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("modules/../../guis", "guis", "Menu"));
    }

    @Test
    void absoluteFormsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("C:/server/guis", "guis", "Menu"));
    }

    @Test
    void theReservedFolderIsRejectedInAnySpelling() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("guis", "guis", "Menu"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("./GUIS/", "guis", "Menu"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFolders.normalize("modules/../guis", "guis", "Menu"));
    }
}
