package org.cubexmc.humanverify.listener;

import org.cubexmc.humanverify.HumanVerifyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Freezes unverified players during verification — movement, interactions,
 * chat, and commands are blocked when the global freeze toggle and
 * the corresponding per-action toggle are both enabled.
 *
 * <p>Players with {@code humanverify.bypass} or who are already verified
 * are never affected.</p>
 */
public final class VerificationEnforcer implements Listener {

    private final HumanVerifyPlugin plugin;

    public VerificationEnforcer(HumanVerifyPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------------------------------------------------------
     * Movement
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        // Only cancel when the block position actually changes (prevents
        // anti-cheat false positives from tiny head-rotation ticks).
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        event.setTo(event.getFrom());
    }

    /* ---------------------------------------------------------------
     * Block / entity interaction
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Damage — both dealing and receiving
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (!(event.getDamager() instanceof Player damager)) return;
        if (plugin.isPendingVerification(damager)) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Item drop
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Inventory — non-verification inventories and drag
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!plugin.isPendingVerification(player)) return;

        Inventory topInv = event.getView().getTopInventory();
        InventoryHolder holder = topInv != null ? topInv.getHolder() : null;

        // Allow clicks inside the verification GUI (handled by the main plugin)
        if (holder instanceof org.cubexmc.humanverify.core.CaptchaHolder) return;

        // Also allow crafting slots (2x2 survival inventory crafting)
        if (event.getClickedInventory() instanceof CraftingInventory) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!plugin.isPendingVerification(player)) return;

        Inventory topInv = event.getView().getTopInventory();
        InventoryHolder holder = topInv != null ? topInv.getHolder() : null;
        if (holder instanceof org.cubexmc.humanverify.core.CaptchaHolder) return;

        event.setCancelled(true);
    }

    /* ---------------------------------------------------------------
     * Chat
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        event.setCancelled(true);
        plugin.scheduleForPlayer(player, () ->
                player.sendMessage(plugin.message("frozen-chat")), 1L);
    }

    /* ---------------------------------------------------------------
     * Commands
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.isFreezeEnabled()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        String msg = event.getMessage(); // always starts with "/"
        for (String allowed : plugin.getCommandWhitelist()) {
            if (msg.toLowerCase(java.util.Locale.ROOT).startsWith(allowed.toLowerCase(java.util.Locale.ROOT))) {
                return; // whitelisted
            }
        }
        event.setCancelled(true);
        plugin.scheduleForPlayer(player, () ->
                player.sendMessage(plugin.message("frozen-command")), 1L);
    }
}
