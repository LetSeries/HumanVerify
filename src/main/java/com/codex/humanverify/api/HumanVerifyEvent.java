package com.codex.humanverify.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a verification request reaches a terminal state. */
public final class HumanVerifyEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final VerificationResult result;

    public HumanVerifyEvent(Player player, VerificationResult result) {
        this.player = player;
        this.result = result;
    }

    public Player getPlayer() {
        return player;
    }

    public VerificationResult getResult() {
        return result;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
