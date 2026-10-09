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
    private List<Integer> expectedSlots;
    private final int maxAttempts;
    private final CompletableFuture<VerificationResult> future;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final java.util.Set<Integer> selectedExpectedSlots = ConcurrentHashMap.newKeySet();
    /**
     * Phase flag for MEMORY (memorize -> recall) and REACTION (wait -> go).
     * {@code false} = phase 1 (look, don't click), {@code true} = phase 2 (click allowed).
     * Other modes never read this flag.
     */
    private final AtomicBoolean armed = new AtomicBoolean(true);
    /** Epoch millis when the challenge was created (for solve-time analysis). */
    private final long createdAt = System.currentTimeMillis();
    /** Epoch millis of the last GUI click, for click-interval analysis. */
    private final java.util.concurrent.atomic.AtomicLong lastClickAt = new java.util.concurrent.atomic.AtomicLong(0L);
    /** Epoch millis when the session became clickable (armed), 0 = immediately. */
    private final java.util.concurrent.atomic.AtomicLong armedAt = new java.util.concurrent.atomic.AtomicLong(0L);
    /** True when created via force (admin/API): success verifies immediately, skipping combo. */
    private final AtomicBoolean force = new AtomicBoolean(false);
    /** Latched when any click interval is impossibly short (script-like). */
    private final AtomicBoolean fastClickSeen = new AtomicBoolean(false);

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
    public boolean isArmed() { return armed.get(); }
    public void setArmed(boolean value) {
        if (value) armedAt.set(System.currentTimeMillis());
        armed.set(value);
    }
    public long getCreatedAt() { return createdAt; }
    public long getArmedAt() { return armedAt.get(); }
    public boolean isForce() { return force.get(); }
    public void setForce(boolean value) { force.set(value); }
    public void markFastClick() { fastClickSeen.set(true); }
    public boolean hadFastClick() { return fastClickSeen.get(); }

    /** Records a GUI click; returns ms since the previous click, or -1 for the first click. */
    public long recordClick() {
        long now = System.currentTimeMillis();
        long prev = lastClickAt.getAndSet(now);
        return prev == 0L ? -1L : now - prev;
    }

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
