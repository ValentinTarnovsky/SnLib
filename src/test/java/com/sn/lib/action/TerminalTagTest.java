package com.sn.lib.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the SnBridge actions-verb security gate: the terminal tag it screens must see
 * THROUGH guard prefixes, so a dangerous tag can never hide behind a guard like
 * {@code [chance=100][console] ...}.
 */
class TerminalTagTest {

    @Test
    void plainTagResolves() {
        assertEquals("message", ActionEngine.terminalTag("[message] hi"));
        assertEquals("console", ActionEngine.terminalTag("[console] op x"));
        assertEquals("sound", ActionEngine.terminalTag("[sound] X 1 1"));
    }

    @Test
    void noLeadingTagIsMessage() {
        assertEquals("message", ActionEngine.terminalTag("just some text"));
        assertEquals("message", ActionEngine.terminalTag("  &aleading spaces"));
    }

    @Test
    void guardsAreStrippedToTheEffectiveTag() {
        // the danger the gate must catch: a command tag hidden behind guards
        assertEquals("console", ActionEngine.terminalTag("[chance=100][console] op attacker"));
        assertEquals("player-as-op", ActionEngine.terminalTag("[right-click][player-as-op] op me"));
        assertEquals("console", ActionEngine.terminalTag("[click=LEFT][chance=50][console] stop"));
    }

    @Test
    void caseIsNormalized() {
        assertEquals("console", ActionEngine.terminalTag("[CONSOLE] op x"));
        assertEquals("message", ActionEngine.terminalTag("[Message] hi"));
    }
}
