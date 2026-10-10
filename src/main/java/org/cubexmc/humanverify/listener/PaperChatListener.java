package org.cubexmc.humanverify.listener;

import org.cubexmc.humanverify.HumanVerifyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.block.PlayerShearBlockEvent;

/**
 * Paper-native chat freeze. On modern Paper builds chat flows through
 * {@link AsyncChatEvent}; the legacy {@code AsyncPlayerChatEvent} may not
 * fire (or fires without effect), so unverified players could otherwise
 * talk freely. This listener is a separate class — registered only when
 * the Paper event class exists — so plain Bukkit/Spigot servers never
 * attempt to load it.
 *
 * <p>The same isolation makes it the home for other Paper-only freeze
 * handlers (item frames, flower pots, lecterns, block shearing).</p>
 */
public final class PaperChatListener implements Listener {

    private final HumanVerifyPlugin plugin;

    public PaperChatListener(HumanVerifyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        if (!plugin.isFreezeChat()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        event.setCancelled(true);
        plugin.scheduleForPlayer(player, () ->
                player.sendMessage(plugin.message("frozen-chat")), 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        // Item-frame rotation/insertion bypasses generic interact handlers.
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlowerPot(PlayerFlowerPotManipulateEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInsertLecternBook(PlayerInsertLecternBookEvent event) {
        // Insert complements the Bukkit-side take-lectern-book guard.
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShearBlock(PlayerShearBlockEvent event) {
        // Beehives / pumpkin shearing bypasses entity-shear protection.
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
