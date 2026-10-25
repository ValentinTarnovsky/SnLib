package com.sn.lib.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionEngine#unregister(Map, Map, String, ActionHandler)}: removal by
 * handler identity, the built-in a custom tag overrode coming back, and a stale disable
 * leaving a newer registration alone.
 */
class ActionUnregisterTest {

    private static final Map<String, ActionHandler> NO_BUILTINS = Map.of();

    @Test
    void removesTheSameInstance() {
        ActionHandler custom = (p, arg, c) -> { };
        Map<String, ActionHandler> handlers = new ConcurrentHashMap<>(Map.of("give-token", custom));

        assertTrue(ActionEngine.unregister(handlers, NO_BUILTINS, "give-token", custom));
        assertFalse(handlers.containsKey("give-token"));
    }

    @Test
    void anotherInstanceIsLeftAlone() {
        ActionHandler oldModule = (p, arg, c) -> { };
        ActionHandler newModule = (p, arg, c) -> { };
        Map<String, ActionHandler> handlers = new ConcurrentHashMap<>(Map.of("give-token", newModule));

        assertFalse(ActionEngine.unregister(handlers, NO_BUILTINS, "give-token", oldModule),
                "the disable of the old module must not remove what the new enable registered");
        assertSame(newModule, handlers.get("give-token"));
    }

    @Test
    void anOverriddenBuiltinComesBack() {
        ActionHandler builtin = (p, arg, c) -> { };
        ActionHandler custom = (p, arg, c) -> { };
        Map<String, ActionHandler> builtins = Map.of("message", builtin);
        Map<String, ActionHandler> handlers = new ConcurrentHashMap<>(Map.of("message", custom));

        assertTrue(ActionEngine.unregister(handlers, builtins, "message", custom));
        assertSame(builtin, handlers.get("message"));
    }

    @Test
    void anUnknownTagIsANoOp() {
        ActionHandler custom = (p, arg, c) -> { };
        Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();

        assertFalse(ActionEngine.unregister(handlers, NO_BUILTINS, "give-token", custom));
        assertTrue(handlers.isEmpty());
    }

    @Test
    void aBuiltinIsNeverRemovedByAForeignHandler() {
        ActionHandler builtin = (p, arg, c) -> { };
        ActionHandler custom = (p, arg, c) -> { };
        Map<String, ActionHandler> builtins = Map.of("message", builtin);
        Map<String, ActionHandler> handlers = new ConcurrentHashMap<>(builtins);

        assertFalse(ActionEngine.unregister(handlers, builtins, "message", custom));
        assertSame(builtin, handlers.get("message"));
    }
}
