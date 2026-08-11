package com.sn.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for the sequencing core of {@link SnFuture#chainSync}. The hop and the log
 * are injected, so every path runs without a server: what is asserted here is that the chained
 * future settles on every reachable path, and that it stays PENDING until the hop has run,
 * which is the property that makes a caller's continuation a successor and not a sibling.
 */
class SnFutureChainTest {

    private final List<String> warns = new ArrayList<>();
    private final Consumer<Runnable> inline = Runnable::run;

    @Test
    void runsTheConsumerAndCompletesWithTheSameValue() {
        List<String> ran = new ArrayList<>();
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, ran::add, true, inline, warns::add);

        assertEquals(List.of("v"), ran);
        assertEquals("v", chained.join());
        assertTrue(warns.isEmpty());
    }

    @Test
    void staysPendingUntilTheHopRunsSoTheCallerIsASuccessor() {
        List<Runnable> queued = new ArrayList<>();
        List<String> order = new ArrayList<>();
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, value -> order.add("publish"), true, queued::add,
                warns::add);
        assertFalse(chained.isDone(), "the chained future must not settle before the hop runs");

        chained.whenComplete((value, error) -> order.add("caller"));
        queued.forEach(Runnable::run);

        assertTrue(chained.isDone());
        assertEquals(List.of("publish", "caller"), order);
    }

    @Test
    void skipsTheConsumerAndPropagatesTheSourceFailureUnchanged() {
        RuntimeException failure = new RuntimeException("boom");
        boolean[] ran = {false};
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, null, failure, value -> ran[0] = true, true, inline,
                warns::add);

        assertFalse(ran[0]);
        assertSame(failure, causeOf(chained));
    }

    @Test
    void completesWithTheValueWhenTheHopIsNotAllowed() {
        boolean[] ran = {false};
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, value -> ran[0] = true, false, inline, warns::add);

        assertFalse(ran[0], "the consumer is skipped exactly as thenSync skips it");
        assertEquals("v", chained.join(), "and the chain still settles, so no wait above it hangs");
        assertTrue(warns.isEmpty());
    }

    @Test
    void failsTheChainWhenTheConsumerThrows() {
        IllegalStateException thrown = new IllegalStateException("consumer");
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, value -> {
            throw thrown;
        }, true, inline, warns::add);

        assertSame(thrown, causeOf(chained));
    }

    @Test
    void completesNormallyAndWarnsOnTheDisableRace() {
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, value -> {
        }, true, task -> {
            throw new IllegalPluginAccessException("disabled");
        }, warns::add);

        assertEquals("v", chained.join());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).startsWith("Chained hop to main discarded"));
    }

    @Test
    void failsTheChainOnAnySchedulingFailureThatIsNotADisable() {
        Error other = new Error("folia");
        CompletableFuture<String> chained = new CompletableFuture<>();

        SnFuture.chainStep(chained, "v", null, value -> {
        }, true, task -> {
            throw other;
        }, warns::add);

        assertSame(other, causeOf(chained));
        assertTrue(warns.isEmpty(), "a platform failure is not reported as a clean skip");
    }

    @Test
    void documentsTheOneWindowThatDoesNotSettle() {
        CompletableFuture<String> chained = new CompletableFuture<>();

        // The hop was accepted and then never run: the server cancelled the task on disable.
        SnFuture.chainStep(chained, "v", null, value -> {
        }, true, task -> {
        }, warns::add);

        assertFalse(chained.isDone(), "known residual: a cancelled hop leaves the chain pending");
    }

    private static Throwable causeOf(CompletableFuture<?> future) {
        try {
            future.join();
            throw new AssertionError("expected the future to have failed");
        } catch (CompletionException e) {
            return e.getCause();
        }
    }
}
