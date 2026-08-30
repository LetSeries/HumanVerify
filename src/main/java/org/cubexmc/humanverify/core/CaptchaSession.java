package org.cubexmc.humanverify.core;

import org.cubexmc.humanverify.api.VerificationResult;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class CaptchaSession {
    private final UUID playerId;
    private final Player player;
    private final CaptchaHolder holder;
    private final ChallengeMode mode;
    private final List<Integer> expectedSlots;
    private final int maxAttempts;
    private final CompletableFuture<VerificationResult> future;
    private int attempts;
    private int progress;
    private boolean completed;
    private final Set<Integer> selectedExpectedSlots = new HashSet<>();

    public CaptchaSession(UUID playerId, Player player, CaptchaHolder holder, ChallengeMode mode,
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

    public UUID getPlayerId() { return playerId; }
    public Player getPlayer() { return player; }
    public CaptchaHolder getHolder() { return holder; }
    public ChallengeMode getMode() { return mode; }
    public int getExpectedSlot() { return expectedSlots.get(progress); }
    public boolean isExpectedSlot(int slot) {
        return expectedSlots.contains(slot) && !selectedExpectedSlots.contains(slot);
    }
    public int getExpectedCount() { return expectedSlots.size(); }
    public CompletableFuture<VerificationResult> getFuture() { return future; }
    public boolean isCompleted() { return completed; }
    public int getMaxAttempts() { return maxAttempts; }

    public int registerWrongAttempt() { return ++attempts; }
    public int getRemainingAttempts() { return Math.max(0, maxAttempts - attempts); }

    public int getProgress() { return progress; }

    public boolean advance() {
        progress++;
        return progress >= expectedSlots.size();
    }

    public boolean advance(int slot) {
        if (!isExpectedSlot(slot)) return false;
        selectedExpectedSlots.add(slot);
        progress++;
        return progress >= expectedSlots.size();
    }

    public boolean complete(VerificationResult result) {
        if (completed) return false;
        completed = true;
        future.complete(result);
        return true;
    }
}
