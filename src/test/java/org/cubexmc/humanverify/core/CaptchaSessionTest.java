package org.cubexmc.humanverify.core;

import org.cubexmc.humanverify.api.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaSessionTest {

    private CaptchaSession session(List<Integer> expectedSlots, int maxAttempts) {
        return new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.SEQUENCE, expectedSlots,
                maxAttempts, new CompletableFuture<>()
        );
    }

    // --- advance (SEQUENCE / single-target) ---

    @Test
    void advanceSequentiallyCompletes() {
        CaptchaSession s = session(List.of(10, 20, 30), 3);
        assertEquals(0, s.getProgress());
        assertFalse(s.advance());
        assertEquals(1, s.getProgress());
        assertFalse(s.advance());
        assertEquals(2, s.getProgress());
        assertTrue(s.advance());
        assertEquals(3, s.getProgress());
    }

    @Test
    void advanceBeyondSizeStaysComplete() {
        CaptchaSession s = session(List.of(5), 3);
        assertTrue(s.advance());
        assertTrue(s.advance()); // already at max — still returns true
    }

    // --- advance(slot) COUNT mode ---

    @Test
    void advanceBySlotCompletesWhenAllSelected() {
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COUNT, List.of(1, 3, 7),
                3, new CompletableFuture<>()
        );
        assertFalse(s.advance(1));
        assertFalse(s.advance(3));
        assertTrue(s.advance(7));
    }

    @Test
    void advanceBySlotRejectsDuplicate() {
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COUNT, List.of(1, 3, 7),
                3, new CompletableFuture<>()
        );
        assertFalse(s.advance(1));
        assertFalse(s.advance(1)); // duplicate — rejected
        assertEquals(1, s.getProgress()); // progress unchanged
    }

    @Test
    void advanceBySlotRejectsWrongSlot() {
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COUNT, List.of(1, 3, 7),
                3, new CompletableFuture<>()
        );
        assertFalse(s.advance(99)); // not in expected list
        assertEquals(0, s.getProgress());
    }

    // --- wrong attempts ---

    @Test
    void wrongAttemptCounting() {
        CaptchaSession s = session(List.of(0), 3);
        assertEquals(3, s.getRemainingAttempts());
        assertEquals(1, s.registerWrongAttempt());
        assertEquals(2, s.getRemainingAttempts());
        assertEquals(2, s.registerWrongAttempt());
        assertEquals(1, s.getRemainingAttempts());
        assertEquals(3, s.registerWrongAttempt());
        assertEquals(0, s.getRemainingAttempts());
        // Can still register beyond max
        assertEquals(4, s.registerWrongAttempt());
        assertEquals(0, s.getRemainingAttempts());
    }

    // --- complete CAS ---

    @Test
    void completeIsIdempotent() {
        CompletableFuture<VerificationResult> f = new CompletableFuture<>();
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COLOR, List.of(0),
                3, f
        );
        assertTrue(s.complete(VerificationResult.SUCCESS));
        assertTrue(f.isDone());
        assertEquals(VerificationResult.SUCCESS, f.getNow(null));
        assertFalse(s.complete(VerificationResult.FAILED)); // second call returns false
        assertEquals(VerificationResult.SUCCESS, f.getNow(null)); // future unchanged
        assertTrue(s.isCompleted());
    }

    @Test
    void completeConcurrentOnlyOneWins() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicBoolean anyWon = new AtomicBoolean();
        AtomicInteger winCount = new AtomicInteger();

        CompletableFuture<VerificationResult> f = new CompletableFuture<>();
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COLOR, List.of(0),
                3, f
        );

        Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            t[i] = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }
                boolean won = s.complete(VerificationResult.SUCCESS);
                if (won) {
                    anyWon.set(true);
                    winCount.incrementAndGet();
                }
            });
            t[i].start();
        }
        for (Thread thread : t) thread.join(2000);

        assertTrue(anyWon.get(), "at least one thread should complete");
        assertEquals(1, winCount.get(), "exactly one thread should win the CAS");
    }

    // --- getExpectedSlot ---

    @Test
    void getExpectedSlotReturnsCurrentProgress() {
        CaptchaSession s = session(List.of(5, 10, 15), 3);
        assertEquals(5, s.getExpectedSlot());
        s.advance();
        assertEquals(10, s.getExpectedSlot());
        s.advance();
        assertEquals(15, s.getExpectedSlot());
        s.advance();
        assertEquals(-1, s.getExpectedSlot()); // completed
    }

    // --- isExpectedSlot ---

    @Test
    void isExpectedSlotExcludesSelected() {
        CaptchaSession s = new CaptchaSession(
                UUID.randomUUID(), null, null,
                ChallengeMode.COUNT, List.of(1, 3, 7),
                3, new CompletableFuture<>()
        );
        assertTrue(s.isExpectedSlot(1));
        s.advance(1);
        assertFalse(s.isExpectedSlot(1)); // already selected
        assertTrue(s.isExpectedSlot(3));
    }
}
