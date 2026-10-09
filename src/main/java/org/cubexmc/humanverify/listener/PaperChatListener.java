package org.cubexmc.humanverify.listener;

import org.cubexmc.humanverify.HumanVerifyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Paper-native chat freeze. On modern Paper builds chat flows through
 * {@link AsyncChatEvent}; the legacy {@code AsyncPlayerChatEvent} may not
 * fire (or fires without effect), so unverified players could otherwise
 * talk freely. This listener is a separate class — registered only when
 * the Paper event class exists — so plain Bukkit/Spigot servers never
 * attempt to load it.
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
}
