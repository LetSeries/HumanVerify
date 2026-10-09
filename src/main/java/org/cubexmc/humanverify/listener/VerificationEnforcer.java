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
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
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
        if (!plugin.isFreezeMovement()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        // getTo() can be null on some Paper builds; a null destination
        // means no movement to correct — never NPE here.
        if (event.getTo() == null) return;
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
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        // Unsigned books can carry arbitrary text out of the freeze:
        // signing/editing is an uncancelled chat-adjacent channel.
        if (!plugin.isFreezeChat()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (event.getEntity().getShooter() instanceof Player player
                && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Damage — both dealing and receiving
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.isFreezeInteract()) return;
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
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Inventory — non-verification inventories and drag
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.isFreezeInteract()) return;
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
        if (!plugin.isFreezeInteract()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!plugin.isPendingVerification(player)) return;

        Inventory topInv = event.getView().getTopInventory();
        InventoryHolder holder = topInv != null ? topInv.getHolder() : null;
        if (holder instanceof org.cubexmc.humanverify.core.CaptchaHolder) return;

        event.setCancelled(true);
    }

    /* ---------------------------------------------------------------
     * Movement — teleport, flight, glide, vehicles
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.isFreezeMovement()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!plugin.isFreezeMovement()) return;
        if (event.isFlying() && plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!plugin.isFreezeMovement()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.isGliding() && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!plugin.isFreezeMovement()) return;
        if (event.getEntered() instanceof Player player && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Inventory extras — swap hands, armor stands, consume, pickup
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        // Arrows have their own pickup event separate from EntityPickupItemEvent.
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (event.getEntity() instanceof Player player && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!plugin.isFreezeInteract()) return;
        if (plugin.isPendingVerification(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        // Item frames / paintings: breaking them yields items, same as block break.
        if (!plugin.isFreezeInteract()) return;
        if (event.getRemover() instanceof Player player
                && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        // Boats / minecarts drop themselves when broken.
        if (!plugin.isFreezeInteract()) return;
        if (event.getAttacker() instanceof Player player
                && plugin.isPendingVerification(player)) {
            event.setCancelled(true);
        }
    }

    /* ---------------------------------------------------------------
     * Chat
     * --------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.isFreezeChat()) return;
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
        if (!plugin.isFreezeCommands()) return;
        Player player = event.getPlayer();
        if (!plugin.isPendingVerification(player)) return;

        String msg = event.getMessage(); // always starts with "/"
        if (HumanVerifyPlugin.isWhitelistedCommand(msg, plugin.getCommandWhitelist())) {
            return; // whitelisted
        }
        event.setCancelled(true);
        plugin.scheduleForPlayer(player, () ->
                player.sendMessage(plugin.message("frozen-command")), 1L);
    }
}
