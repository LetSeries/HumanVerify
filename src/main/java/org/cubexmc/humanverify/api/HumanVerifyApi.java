package org.cubexmc.humanverify.api;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public service registered through Bukkit's ServicesManager.
 * Other plugins can obtain it with Bukkit.getServicesManager().load(HumanVerifyApi.class).
 */
public interface HumanVerifyApi {
    boolean isVerified(UUID playerId);

    default boolean isVerified(Player player) {
        return isVerified(player.getUniqueId());
    }

    CompletableFuture<VerificationResult> requestVerification(Player player);

    /** Starts a new challenge even when the player is already verified or bypassed. */
    CompletableFuture<VerificationResult> requestVerification(Player player, boolean force);

    void markVerified(UUID playerId);

    default void markVerified(Player player) {
        markVerified(player.getUniqueId());
    }

    void revokeVerification(UUID playerId);
}
