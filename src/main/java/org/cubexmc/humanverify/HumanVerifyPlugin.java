package org.cubexmc.humanverify;

import org.cubexmc.humanverify.api.HumanVerifyApi;
import org.cubexmc.humanverify.api.HumanVerifyEvent;
import org.cubexmc.humanverify.api.VerificationResult;
import org.cubexmc.humanverify.command.HumanVerifyCommand;
import org.cubexmc.humanverify.core.CaptchaHolder;
import org.cubexmc.humanverify.core.CaptchaSession;
import org.cubexmc.humanverify.core.ChallengeMode;
import org.cubexmc.humanverify.listener.VerificationEnforcer;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class HumanVerifyPlugin extends JavaPlugin implements Listener, HumanVerifyApi {

    // -- Session & verified state ---------------------------------------------------
    private final Map<UUID, CaptchaSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();

    // -- Materials ------------------------------------------------------------------
    private Material correctMaterial;
    private Material wrongMaterial;
    private Material buttonMaterial;
    private Material targetMaterial;
    private Material sequenceMaterial;
    private Material countMaterial;
    private Material oddOneOutMaterial;
    private Material oddOneOutTargetMaterial;

    // -- Mode config ----------------------------------------------------------------
    private ChallengeMode configuredMode;
    private List<ChallengeMode> enabledModes;

    // -- Enforcement config ---------------------------------------------------------
    private boolean freezeMovement;
    private boolean freezeInteract;
    private boolean freezeChat;
    private boolean freezeCommands;
    private List<String> commandWhitelist;

    // -- Fail / expire action -------------------------------------------------------
    private FailAction failAction;
    private FailAction expireAction;
    private long retryDelayTicks;

    // -- Shutdown flag (prevents RETRY/KICK during onDisable) -----------------------
    private volatile boolean shuttingDown;

    // ================================================================================
    //  Lifecycle
    // ================================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new VerificationEnforcer(this), this);
        Bukkit.getServicesManager().register(HumanVerifyApi.class, this, this, ServicePriority.Normal);

        PluginCommand command = getCommand("humanverify");
        if (command != null) {
            HumanVerifyCommand executor = new HumanVerifyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Covers /reload and plugin hot-reload scenarios
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getConfig().getBoolean("auto-verify-on-join", true)) {
                scheduleForPlayer(player, () -> beginAutomaticVerification(player), 1L);
            }
        }
        getLogger().info("HumanVerify enabled (Paper/Folia/Purpur compatible).");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        for (CaptchaSession session : new ArrayList<>(sessions.values())) {
            finish(session, VerificationResult.CANCELLED, false);
        }
        sessions.clear();
        Bukkit.getServicesManager().unregister(HumanVerifyApi.class, this);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        ensureConfigDefaults();
        loadSettings();
    }

    // ================================================================================
    //  Config migration & validation
    // ================================================================================

    private void ensureConfigDefaults() {
        var cfg = getConfig();
        boolean changed = false;

        // v2 defaults
        if (!cfg.isSet("config-version")) {
            cfg.set("config-version", 2);
            changed = true;
        }

        // Helper: if key missing, set default and mark changed
        changed |= setDefault(cfg, "freeze-unverified", true);
        changed |= setDefault(cfg, "freeze-movement", true);
        changed |= setDefault(cfg, "freeze-interact", true);
        changed |= setDefault(cfg, "freeze-chat", true);
        changed |= setDefault(cfg, "freeze-commands", true);
        changed |= setDefault(cfg, "command-whitelist", List.of("/login", "/register"));
        changed |= setDefault(cfg, "fail-action", "RETRY");
        changed |= setDefault(cfg, "expire-action", "RETRY");
        changed |= setDefault(cfg, "fail-kick-message", "&c验证失败次数过多，已被移出服务器。");
        changed |= setDefault(cfg, "expire-kick-message", "&c验证超时，已被移出服务器。");
        changed |= setDefault(cfg, "messages.bypassed", "&a你拥有验证豁免权限，无需验证。");
        changed |= setDefault(cfg, "messages.retry", "&e验证未通过，已为你重新开始验证。");
        changed |= setDefault(cfg, "messages.frozen-chat", "&c验证完成前无法聊天。");
        changed |= setDefault(cfg, "messages.frozen-command", "&c验证完成前无法使用指令。");

        if (changed) {
            cfg.set("config-version", 2);
            saveConfig();
            getLogger().info("Configuration migrated to version 2 (new defaults written).");
        }
    }

    /** Set a default value only if the key is not already present. Returns true if changed. */
    private static boolean setDefault(org.bukkit.configuration.file.FileConfiguration cfg, String path, Object value) {
        if (cfg.isSet(path)) return false;
        cfg.set(path, value);
        return true;
    }

    private void loadSettings() {
        var cfg = getConfig();

        buttonMaterial = materialOrDefault("button-material", Material.WHITE_WOOL);
        Material[] reserved = {buttonMaterial};

        correctMaterial   = distinctMaterial("correct-material", Material.LIME_WOOL, reserved);
        wrongMaterial     = distinctMaterial("wrong-material", Material.RED_WOOL, reserved);
        targetMaterial    = distinctMaterial("target-material", Material.DIAMOND, reserved);
        sequenceMaterial  = distinctMaterial("sequence-material", Material.YELLOW_WOOL, reserved);
        countMaterial     = distinctMaterial("count-material", Material.EMERALD, reserved);
        oddOneOutMaterial = distinctMaterial("odd-one-out-material", Material.IRON_BLOCK, reserved);
        oddOneOutTargetMaterial = distinctMaterial("odd-one-out-target-material", Material.GOLD_BLOCK, new Material[]{buttonMaterial, oddOneOutMaterial});

        configuredMode = parseMode(cfg.getString("verification-mode", "RANDOM"));
        enabledModes   = resolveEnabledModes(cfg.getStringList("enabled-modes"));

        // Enforcement
        boolean globalFreeze = cfg.getBoolean("freeze-unverified", true);
        freezeMovement = globalFreeze && cfg.getBoolean("freeze-movement", true);
        freezeInteract = globalFreeze && cfg.getBoolean("freeze-interact", true);
        freezeChat     = globalFreeze && cfg.getBoolean("freeze-chat", true);
        freezeCommands = globalFreeze && cfg.getBoolean("freeze-commands", true);
        commandWhitelist = cfg.getStringList("command-whitelist");

        // Fail / expire
        failAction   = parseAction(cfg.getString("fail-action", "RETRY"));
        expireAction = parseAction(cfg.getString("expire-action", "RETRY"));
        retryDelayTicks = Math.max(1L, cfg.getLong("retry-delay-ticks", 20L));
    }

    // ================================================================================
    //  Material helpers
    // ================================================================================

    private Material materialOrDefault(String path, Material fallback) {
        Material m = Material.matchMaterial(getConfig().getString(path, fallback.name()));
        return m != null && m.isItem() ? m : fallback;
    }

    /**
     * Resolve a material that must differ from every entry in {@code reserved}.
     * On conflict, falls back to {@code fallback}; if fallback is also reserved,
     * tries safe candidates; last resort is STONE.
     */
    private Material distinctMaterial(String path, Material fallback, Material[] reserved) {
        Material m = materialOrDefault(path, fallback);
        if (!contains(reserved, m)) return m;

        getLogger().warning(path + " must differ from reserved materials; using fallback " + fallback + ".");
        if (!contains(reserved, fallback)) return fallback;

        for (Material c : List.of(Material.LIME_WOOL, Material.RED_WOOL, Material.YELLOW_WOOL, Material.GOLD_BLOCK, Material.DIAMOND)) {
            if (!contains(reserved, c)) return c;
        }
        return Material.STONE;
    }

    private static boolean contains(Material[] arr, Material m) {
        for (Material r : arr) if (r == m) return true;
        return false;
    }

    // ================================================================================
    //  Mode / action parsing (static for testability)
    // ================================================================================

    /** Parse a ChallengeMode; unknown → RANDOM. */
    static ChallengeMode parseMode(String value) {
        try {
            return ChallengeMode.valueOf(value == null ? "RANDOM" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ChallengeMode.RANDOM;
        }
    }

    /** Resolve enabled-modes list (de-duplicated, RANDOM excluded, empty → [COLOR]). */
    static List<ChallengeMode> resolveEnabledModes(List<String> raw) {
        List<ChallengeMode> modes = new ArrayList<>();
        for (String v : raw) {
            ChallengeMode m = parseMode(v);
            if (m != ChallengeMode.RANDOM && !modes.contains(m)) modes.add(m);
        }
        if (modes.isEmpty()) modes.add(ChallengeMode.COLOR);
        return List.copyOf(modes);
    }

    /** Parse FailAction; unknown → RETRY. */
    static FailAction parseAction(String value) {
        try {
            return FailAction.valueOf(value == null ? "RETRY" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FailAction.RETRY;
        }
    }

    // ================================================================================
    //  Grid geometry helpers (package-visible, static for testing)
    // ================================================================================

    static int normalizedSize(int configured) {
        int size = Math.max(9, Math.min(54, configured));
        return size - (size % 9);
    }

    static int centerSlot(int size) {
        int rows = size / 9;
        return (rows / 2) * 9 + 4;
    }

    static int cornerSlot(int size, java.util.Random rng) {
        int rows = size / 9;
        int[] corners = {0, 8, (rows - 1) * 9, size - 1};
        return corners[rng.nextInt(corners.length)];
    }

    // ================================================================================
    //  Public API — HumanVerifyApi
    // ================================================================================

    @Override
    public boolean isVerified(UUID playerId) {
        return verified.contains(playerId);
    }

    /** Returns true when the player has an active (unfinished) session and is not yet verified. */
    public boolean isPendingVerification(Player player) {
        if (player == null) return false;
        UUID id = player.getUniqueId();
        return sessions.containsKey(id) && !verified.contains(id);
    }

    @Override
    public CompletableFuture<VerificationResult> requestVerification(Player player) {
        return requestVerification(player, false);
    }

    @Override
    public CompletableFuture<VerificationResult> requestVerification(Player player, boolean force) {
        CompletableFuture<VerificationResult> already = new CompletableFuture<>();
        if (player == null || !player.isOnline()) {
            already.complete(VerificationResult.CANCELLED);
            return already;
        }
        if (!force && (player.hasPermission("humanverify.bypass") || isVerified(player))) {
            already.complete(VerificationResult.SUCCESS);
            // Fire event for consistency (bypass / already-verified fast path)
            scheduleForPlayer(player, () ->
                    Bukkit.getPluginManager().callEvent(new HumanVerifyEvent(player, VerificationResult.SUCCESS)), 1L);
            return already;
        }
        if (force) verified.remove(player.getUniqueId());

        // Cancel any existing session
        CaptchaSession previous = sessions.remove(player.getUniqueId());
        if (previous != null) {
            finish(previous, VerificationResult.CANCELLED, false);
        }

        int size = normalizedSize(getConfig().getInt("challenge-size", 27));
        int maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        CaptchaHolder holder = new CaptchaHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, size, titleComponent());
        holder.setInventory(inventory);

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(i);
        Collections.shuffle(slots);

        ChallengeMode mode = selectMode();
        int sequenceLength = Math.max(2, Math.min(size, getConfig().getInt("sequence-length", 3)));
        int targetCount    = Math.max(1, Math.min(size - 1, getConfig().getInt("target-count", 3)));

        List<Integer> expectedSlots = switch (mode) {
            case SEQUENCE -> new ArrayList<>(slots.subList(0, sequenceLength));
            case COUNT    -> new ArrayList<>(slots.subList(0, targetCount));
            case CENTER   -> List.of(centerSlot(size));
            case CORNER   -> List.of(cornerSlot(size, ThreadLocalRandom.current()));
            default       -> List.of(slots.get(0));
        };

        for (int slot : slots) {
            int step = expectedSlots.indexOf(slot);
            boolean oddOneOut = mode == ChallengeMode.ODD_ONE_OUT && slot == slots.get(0);
            inventory.setItem(slot, createButton(mode, step < 0 ? (oddOneOut ? 1 : 0) : step + 1));
        }

        CompletableFuture<VerificationResult> future = new CompletableFuture<>();
        CaptchaSession session = new CaptchaSession(player.getUniqueId(), player, holder, mode, expectedSlots, maxAttempts, future);
        sessions.put(player.getUniqueId(), session);

        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(player.getUniqueId());
            if (current != session || session.isCompleted() || !player.isOnline()) return;
            player.openInventory(inventory);
        }, 0L);

        // Timeout
        long timeoutTicks = Math.max(1L, Duration.ofSeconds(Math.max(5, getConfig().getLong("timeout-seconds", 60))).toSeconds() * 20L);
        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(player.getUniqueId());
            if (current == session && !session.isCompleted()) {
                player.sendMessage(message("expired"));
                handleTerminal(session, VerificationResult.EXPIRED);
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

    // ================================================================================
    //  Enforcement queries (used by VerificationEnforcer)
    // ================================================================================

    public boolean isFreezeEnabled() { return true; } // global toggle always on; per-action handled in enforcer
    public List<String> getCommandWhitelist() { return commandWhitelist; }

    // ================================================================================
    //  Event handlers (Listener)
    // ================================================================================

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

        // --- COUNT mode: arbitrary order ---
        if (session.getMode() == ChallengeMode.COUNT && session.isExpectedSlot(slot)) {
            if (session.advance(slot)) {
                verified.add(player.getUniqueId());
                player.sendMessage(message("success"));
                finish(session, VerificationResult.SUCCESS, true);
            } else {
                // Mark slot as completed visually
                markSlotCompleted(event.getView().getTopInventory(), slot);
                player.sendMessage(message("count-progress")
                        .replace("{current}", String.valueOf(session.getProgress()))
                        .replace("{total}", String.valueOf(session.getExpectedCount())));
            }
            return;
        }

        // --- SEQUENCE / single-target modes: strict order ---
        if (session.getMode() != ChallengeMode.COUNT && slot == session.getExpectedSlot()) {
            if (session.advance()) {
                verified.add(player.getUniqueId());
                player.sendMessage(message("success"));
                finish(session, VerificationResult.SUCCESS, true);
            } else {
                // Mark slot as completed visually
                markSlotCompleted(event.getView().getTopInventory(), slot);
                player.sendMessage(message("sequence-progress")
                        .replace("{current}", String.valueOf(session.getProgress())));
            }
            return;
        }

        // --- Wrong click ---
        int attempts = session.registerWrongAttempt();
        if (session.getMode() != ChallengeMode.SEQUENCE && session.getMode() != ChallengeMode.COUNT) {
            event.getView().getTopInventory().setItem(slot, createWrongButton());
        }
        player.sendMessage(message("wrong").replace("{remaining}", String.valueOf(session.getRemainingAttempts())));
        if (attempts >= session.getMaxAttempts()) {
            player.sendMessage(message("failed"));
            handleTerminal(session, VerificationResult.FAILED);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CaptchaHolder holder)) return;
        CaptchaSession session = sessions.get(holder.getPlayerId());
        if (session == null || session.isCompleted() || !(event.getPlayer() instanceof Player player)) return;

        // Keep mandatory verification: closing the GUI simply reopens it.
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

    // ================================================================================
    //  Terminal handling — RETRY / KICK
    // ================================================================================

    /**
     * Called after a session reaches FAILED or EXPIRED.
     * Retries (re-opens verification) or kicks the player, based on config.
     */
    private void handleTerminal(CaptchaSession session, VerificationResult result) {
        if (shuttingDown) {
            finish(session, result, true);
            return;
        }

        Player player = session.getPlayer();
        FailAction action = (result == VerificationResult.FAILED) ? failAction : expireAction;

        finish(session, result, true);

        if (!player.isOnline()) return;

        if (action == FailAction.KICK) {
            String kickMsg = (result == VerificationResult.FAILED)
                    ? getConfig().getString("fail-kick-message", "&c验证失败次数过多，已被移出服务器。")
                    : getConfig().getString("expire-kick-message", "&c验证超时，已被移出服务器。");
            kickPlayer(player, kickMsg);
        } else {
            // RETRY: re-open verification after a short delay
            player.sendMessage(message("retry"));
            scheduleForPlayer(player, () -> {
                if (player.isOnline() && !isVerified(player)) {
                    requestVerification(player, true);
                }
            }, retryDelayTicks);
        }
    }

    /** Kick via EntityScheduler (Folia-safe). */
    private void kickPlayer(Player player, String rawMessage) {
        String colored = color(rawMessage);
        Component kickComponent = LegacyComponentSerializer.legacySection().deserialize(colored);
        scheduleForPlayer(player, () -> {
            if (player.isOnline()) {
                player.kick(kickComponent);
            }
        }, 1L);
    }

    // ================================================================================
    //  Finish & event
    // ================================================================================

    private void finish(CaptchaSession session, VerificationResult result, boolean closeInventory) {
        if (!session.complete(result)) return;
        sessions.remove(session.getPlayerId(), session);

        if (closeInventory && session.getPlayer() != null && session.getPlayer().isOnline()) {
            scheduleForPlayer(session.getPlayer(), () -> {
                if (session.getPlayer().getOpenInventory().getTopInventory().getHolder() == session.getHolder()) {
                    session.getPlayer().closeInventory();
                }
            }, 0L);
        }

        // Always fire event (including offline / quit scenarios)
        fireEvent(session.getPlayer(), result);
    }

    private void fireEvent(Player player, VerificationResult result) {
        if (player != null) {
            Bukkit.getPluginManager().callEvent(new HumanVerifyEvent(player, result));
        }
    }

    // ================================================================================
    //  GUI builders
    // ================================================================================

    private ItemStack createButton(ChallengeMode mode, int step) {
        boolean target = step > 0;
        Material material = switch (mode) {
            case MATERIAL     -> target ? targetMaterial : buttonMaterial;
            case SEQUENCE     -> target ? sequenceMaterial : buttonMaterial;
            case COUNT        -> target ? countMaterial : buttonMaterial;
            case ODD_ONE_OUT  -> target ? oddOneOutTargetMaterial : oddOneOutMaterial;
            case CENTER, CORNER -> target ? correctMaterial : buttonMaterial;
            case COLOR        -> target ? correctMaterial : buttonMaterial;
            default -> buttonMaterial; // RANDOM is resolved before reaching here
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = target
                    ? (mode == ChallengeMode.SEQUENCE || mode == ChallengeMode.COUNT
                        ? ChatColor.YELLOW + (mode == ChallengeMode.COUNT ? "点击目标 " : "验证步骤 ") + step
                        : ChatColor.GREEN + "点击这里")
                    : ChatColor.WHITE + "验证按钮";
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

    /** Mark a slot as "completed" visually (green + "已完成") for SEQUENCE/COUNT progress. */
    private void markSlotCompleted(Inventory inventory, int slot) {
        ItemStack item = new ItemStack(correctMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(ChatColor.GREEN + "已完成"));
            meta.lore(List.of(legacy(ChatColor.GREEN + "已点击")));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    // ================================================================================
    //  Messages & utilities
    // ================================================================================

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

    /** Title as a Component (preserves color codes). */
    private Component titleComponent() {
        String raw = message("title");
        return LegacyComponentSerializer.legacySection().deserialize(raw);
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = getConfig().getString("messages." + key, key);
        return color(prefix + value);
    }

    String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }

    private void beginAutomaticVerification(Player player) {
        if (player.isOnline() && !player.hasPermission("humanverify.bypass") && !isVerified(player)) {
            player.sendMessage(message("join"));
            requestVerification(player);
        }
    }

    /** EntityScheduler — safe on both Paper and Folia. */
    public void scheduleForPlayer(Player player, Runnable task, long delayTicks) {
        long safeDelay = Math.max(0L, delayTicks);
        player.getScheduler().runDelayed(this, scheduledTask -> {
            if (player.isOnline()) task.run();
        }, null, safeDelay);
    }

    /** Terminal state action. */
    public enum FailAction {
        RETRY,
        KICK
    }
}
