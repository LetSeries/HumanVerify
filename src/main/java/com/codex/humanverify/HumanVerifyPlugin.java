package com.codex.humanverify;

import com.codex.humanverify.api.HumanVerifyApi;
import com.codex.humanverify.api.HumanVerifyEvent;
import com.codex.humanverify.api.VerificationResult;
import com.codex.humanverify.command.HumanVerifyCommand;
import com.codex.humanverify.core.CaptchaHolder;
import com.codex.humanverify.core.CaptchaSession;
import com.codex.humanverify.core.ChallengeMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class HumanVerifyPlugin extends JavaPlugin implements Listener, HumanVerifyApi {
    private final Map<UUID, CaptchaSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private Material correctMaterial;
    private Material wrongMaterial;
    private Material buttonMaterial;
    private Material targetMaterial;
    private Material sequenceMaterial;
    private ChallengeMode configuredMode;
    private List<ChallengeMode> enabledModes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getServicesManager().register(HumanVerifyApi.class, this, this, ServicePriority.Normal);

        PluginCommand command = getCommand("humanverify");
        if (command != null) {
            HumanVerifyCommand executor = new HumanVerifyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Covers /reload and plugin hot-reload scenarios without using the global scheduler.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getConfig().getBoolean("auto-verify-on-join", true)) {
                scheduleForPlayer(player, () -> beginAutomaticVerification(player), 1L);
            }
        }
        getLogger().info("HumanVerify enabled (Paper/Folia/Purpur compatible).");
    }

    @Override
    public void onDisable() {
        for (CaptchaSession session : new ArrayList<>(sessions.values())) {
            finish(session, VerificationResult.CANCELLED, false);
        }
        sessions.clear();
        Bukkit.getServicesManager().unregister(HumanVerifyApi.class, this);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadSettings();
    }

    private void loadSettings() {
        buttonMaterial = materialOrDefault("button-material", Material.WHITE_WOOL);
        correctMaterial = distinctMaterial("correct-material", Material.LIME_WOOL, buttonMaterial);
        wrongMaterial = distinctMaterial("wrong-material", Material.RED_WOOL, buttonMaterial);
        targetMaterial = distinctMaterial("target-material", Material.DIAMOND, buttonMaterial);
        sequenceMaterial = distinctMaterial("sequence-material", Material.YELLOW_WOOL, buttonMaterial);
        configuredMode = modeOrDefault(getConfig().getString("verification-mode", "RANDOM"));
        enabledModes = enabledModes();
    }

    private Material materialOrDefault(String path, Material fallback) {
        Material material = Material.matchMaterial(getConfig().getString(path, fallback.name()));
        return material == null || !material.isItem() ? fallback : material;
    }

    private Material distinctMaterial(String path, Material fallback, Material reserved) {
        Material material = materialOrDefault(path, fallback);
        if (material != reserved) return material;
        getLogger().warning("Configured " + path + " must differ from button-material; using " + fallback + ".");
        if (fallback != reserved) return fallback;
        for (Material candidate : List.of(Material.LIME_WOOL, Material.RED_WOOL, Material.YELLOW_WOOL, Material.GOLD_BLOCK)) {
            if (candidate != reserved) return candidate;
        }
        return Material.STONE;
    }

    private void beginAutomaticVerification(Player player) {
        if (player.isOnline() && !player.hasPermission("humanverify.bypass") && !isVerified(player)) {
            player.sendMessage(message("join"));
            requestVerification(player);
        }
    }

    @Override
    public boolean isVerified(UUID playerId) {
        return verified.contains(playerId);
    }

    @Override
    public CompletableFuture<VerificationResult> requestVerification(Player player) {
        return requestVerification(player, false);
    }

    /** Starts a challenge even for verified or bypass-permission players. */
    public CompletableFuture<VerificationResult> requestVerification(Player player, boolean force) {
        CompletableFuture<VerificationResult> already = new CompletableFuture<>();
        if (player == null || !player.isOnline()) {
            already.complete(VerificationResult.CANCELLED);
            return already;
        }
        if (!force && (player.hasPermission("humanverify.bypass") || isVerified(player))) {
            already.complete(VerificationResult.SUCCESS);
            return already;
        }

        CaptchaSession previous = sessions.remove(player.getUniqueId());
        if (previous != null) {
            finish(previous, VerificationResult.CANCELLED, false);
        }

        int size = normalizedSize(getConfig().getInt("challenge-size", 27));
        int maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        CaptchaHolder holder = new CaptchaHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text(title()));
        holder.setInventory(inventory);

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(i);
        Collections.shuffle(slots);
        ChallengeMode mode = selectMode();
        int sequenceLength = Math.max(2, Math.min(size, getConfig().getInt("sequence-length", 3)));
        List<Integer> expectedSlots = mode == ChallengeMode.SEQUENCE
                ? new ArrayList<>(slots.subList(0, sequenceLength))
                : List.of(slots.get(0));
        for (int slot : slots) {
            int step = expectedSlots.indexOf(slot);
            inventory.setItem(slot, createButton(mode, step < 0 ? 0 : step + 1));
        }

        CompletableFuture<VerificationResult> future = new CompletableFuture<>();
        CaptchaSession session = new CaptchaSession(player.getUniqueId(), player, holder, mode, expectedSlots, maxAttempts, future);
        sessions.put(player.getUniqueId(), session);

        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(player.getUniqueId());
            if (current != session || session.isCompleted() || !player.isOnline()) return;
            player.openInventory(inventory);
        }, 0L);

        long timeoutTicks = Math.max(1L, Duration.ofSeconds(Math.max(5, getConfig().getLong("timeout-seconds", 60))).toSeconds() * 20L);
        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(player.getUniqueId());
            if (current == session && !session.isCompleted()) {
                player.sendMessage(message("expired"));
                finish(session, VerificationResult.EXPIRED, true);
            }
        }, timeoutTicks);
        return future;
    }

    @Override
    public void markVerified(UUID playerId) {
        verified.add(playerId);
        CaptchaSession session = sessions.remove(playerId);
        if (session != null) finish(session, VerificationResult.SUCCESS, true);
    }

    @Override
    public void revokeVerification(UUID playerId) {
        verified.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("auto-verify-on-join", true)) {
            scheduleForPlayer(event.getPlayer(), () -> beginAutomaticVerification(event.getPlayer()), 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CaptchaHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.getPlayerId().equals(player.getUniqueId())) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getHolder() != holder || session.isCompleted()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        if (slot == session.getExpectedSlot()) {
            if (session.advance()) {
                verified.add(player.getUniqueId());
                player.sendMessage(message("success"));
                finish(session, VerificationResult.SUCCESS, true);
            } else {
                player.sendMessage(message("sequence-progress")
                        .replace("{current}", String.valueOf(session.getProgress())));
            }
            return;
        }

        int attempts = session.registerWrongAttempt();
        if (session.getMode() != ChallengeMode.SEQUENCE) {
            event.getView().getTopInventory().setItem(slot, createWrongButton());
        }
        player.sendMessage(message("wrong").replace("{remaining}", String.valueOf(session.getRemainingAttempts())));
        if (attempts >= sessionMaxAttempts(session)) {
            player.sendMessage(message("failed"));
            finish(session, VerificationResult.FAILED, true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CaptchaHolder holder)) return;
        CaptchaSession session = sessions.get(holder.getPlayerId());
        if (session == null || session.isCompleted() || !(event.getPlayer() instanceof Player player)) return;

        // Keep automatic/API verification mandatory: closing the GUI simply reopens it.
        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(holder.getPlayerId());
            if (current == session && !session.isCompleted() && player.isOnline()) {
                player.openInventory(holder.getInventory());
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CaptchaSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) finish(session, VerificationResult.CANCELLED, false);
        verified.remove(event.getPlayer().getUniqueId());
    }

    private int sessionMaxAttempts(CaptchaSession session) {
        return session.getMaxAttempts();
    }

    private void finish(CaptchaSession session, VerificationResult result, boolean closeInventory) {
        if (!session.complete(result)) return;
        sessions.remove(session.getPlayerId(), session);
        if (closeInventory && session.getPlayer().isOnline()) {
            scheduleForPlayer(session.getPlayer(), () -> {
                if (session.getPlayer().getOpenInventory().getTopInventory().getHolder() == session.getHolder()) {
                    session.getPlayer().closeInventory();
                }
            }, 0L);
        }
        fireEvent(session.getPlayer(), result);
    }

    private void fireEvent(Player player, VerificationResult result) {
        if (player.isOnline()) Bukkit.getPluginManager().callEvent(new HumanVerifyEvent(player, result));
    }

    private ItemStack createButton(ChallengeMode mode, int step) {
        boolean target = step > 0;
        Material material = switch (mode) {
            case MATERIAL -> target ? targetMaterial : buttonMaterial;
            case SEQUENCE -> target ? sequenceMaterial : buttonMaterial;
            case COLOR, RANDOM -> target ? correctMaterial : buttonMaterial;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = target ? (mode == ChallengeMode.SEQUENCE
                    ? ChatColor.YELLOW + "验证步骤 " + step
                    : ChatColor.GREEN + "点击这里") : ChatColor.WHITE + "验证按钮";
            meta.displayName(legacy(name));
            meta.lore(List.of(legacy(instructions(mode))));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createWrongButton() {
        ItemStack item = new ItemStack(wrongMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(ChatColor.RED + "选择错误"));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private int normalizedSize(int configured) {
        int size = Math.max(9, Math.min(54, configured));
        return size - (size % 9);
    }

    private ChallengeMode modeOrDefault(String value) {
        try {
            return ChallengeMode.valueOf(value == null ? "RANDOM" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Unknown verification-mode '" + value + "'; using RANDOM.");
            return ChallengeMode.RANDOM;
        }
    }

    private List<ChallengeMode> enabledModes() {
        List<ChallengeMode> modes = new ArrayList<>();
        for (String value : getConfig().getStringList("enabled-modes")) {
            ChallengeMode mode = modeOrDefault(value);
            if (mode != ChallengeMode.RANDOM && !modes.contains(mode)) modes.add(mode);
        }
        if (modes.isEmpty()) modes.add(ChallengeMode.COLOR);
        return List.copyOf(modes);
    }

    private ChallengeMode selectMode() {
        if (configuredMode != ChallengeMode.RANDOM) return configuredMode;
        return enabledModes.get(ThreadLocalRandom.current().nextInt(enabledModes.size()));
    }

    private String instructions(ChallengeMode mode) {
        String key = "messages.instructions-" + mode.getConfigKey();
        String value = getConfig().getString(key);
        if (value == null) value = getConfig().getString("messages.instructions", "");
        return color(value);
    }

    private String title() {
        return ChatColor.stripColor(message("title"));
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = getConfig().getString("messages." + key, key);
        return color(prefix + value);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }

    /** EntityScheduler is safe on both Paper and Folia; delay is measured in server ticks. */
    public void scheduleForPlayer(Player player, Runnable task, long delayTicks) {
        player.getScheduler().runDelayed(this, scheduledTask -> {
            if (player.isOnline()) task.run();
        }, null, delayTicks);
    }
}
