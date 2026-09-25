package org.cubexmc.humanverify.core;

import org.cubexmc.humanverify.api.VerificationResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptchaSession {
    private final java.util.UUID playerId;
    private final org.bukkit.entity.Player player;
    private final CaptchaHolder holder;
    private final ChallengeMode mode;
    private final List<Integer> expectedSlots;
    private final int maxAttempts;
    private final CompletableFuture<VerificationResult> future;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final java.util.Set<Integer> selectedExpectedSlots = ConcurrentHashMap.newKeySet();

    public CaptchaSession(java.util.UUID playerId, org.bukkit.entity.Player player, CaptchaHolder holder, ChallengeMode mode,
                          List<Integer> expectedSlots,
                          int maxAttempts, CompletableFuture<VerificationResult> future) {
        this.playerId = playerId;
        this.player = player;
        this.holder = holder;
        this.mode = mode;
        this.expectedSlots = List.copyOf(expectedSlots);
        this.maxAttempts = maxAttempts;
        this.future = future;
    }

    public java.util.UUID getPlayerId() { return playerId; }
    public org.bukkit.entity.Player getPlayer() { return player; }
    public CaptchaHolder getHolder() { return holder; }
    public ChallengeMode getMode() { return mode; }
    public CompletableFuture<VerificationResult> getFuture() { return future; }
    public boolean isCompleted() { return completed.get(); }
    public int getMaxAttempts() { return maxAttempts; }

    public int getExpectedSlot() {
        int p = progress.get();
        if (p >= expectedSlots.size()) return -1;
        return expectedSlots.get(p);
    }

    public boolean isExpectedSlot(int slot) {
        return expectedSlots.contains(slot) && !selectedExpectedSlots.contains(slot);
    }

    public int getExpectedCount() { return expectedSlots.size(); }

    public int registerWrongAttempt() { return attempts.incrementAndGet(); }
    public int getRemainingAttempts() { return Math.max(0, maxAttempts - attempts.get()); }

    public int getProgress() { return progress.get(); }

    public synchronized boolean advance() {
        int next = progress.incrementAndGet();
        return next >= expectedSlots.size();
    }

    public synchronized boolean advance(int slot) {
        if (!expectedSlots.contains(slot) || selectedExpectedSlots.contains(slot)) return false;
        selectedExpectedSlots.add(slot);
        int next = progress.incrementAndGet();
        return next >= expectedSlots.size();
    }

    /** CAS-based complete: returns true only on the first call. */
    public boolean complete(VerificationResult result) {
        if (completed.compareAndSet(false, true)) {
            future.complete(result);
            return true;
        }
        return false;
    }
}
