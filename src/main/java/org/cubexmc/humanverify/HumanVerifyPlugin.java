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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    // Players waiting for a RETRY re-open (or join-delayed auto verify):
    // no active session yet, but freezing must still apply.
    private final Set<UUID> retryPending = ConcurrentHashMap.newKeySet();

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
    private int mathMaxSum;
    private int mathOptionCount;
    private int memoryCount;
    private long memoryShowTicks;
    private long reactionBaseTicks;
    private long reactionExtraTicks;
    // Per-session MATH data: question text + option slot -> displayed number.
    private final Map<UUID, MathChallenge> mathChallenges = new ConcurrentHashMap<>();

    // -- Enforcement config ---------------------------------------------------------
    private boolean freezeUnverified;
    private boolean freezeMovement;
    private boolean freezeInteract;
    private boolean freezeChat;
    private boolean freezeCommands;
    private List<String> commandWhitelist;

    // -- Fail / expire action -------------------------------------------------------
    private FailAction failAction;
    private FailAction expireAction;
    private long retryDelayTicks;

    // -- Difficulty escalation ------------------------------------------------------
    private boolean difficultyEscalation;
    private int escalationStep;
    private int escalationMaxLevel;
    private long escalationTimeoutPenalty;
    private int escalationMathBonus;
    private final Map<UUID, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    // -- Anti-script (click flood) ----------------------------------------------------
    private boolean antiFlood;
    private long clickWindowMs;
    private int clickMaxClicks;
    private final Map<UUID, Deque<Long>> clickWindows = new ConcurrentHashMap<>();

    // -- Self-service verify cooldown -------------------------------------------------
    private long verifyCooldownSeconds;
    private final Map<UUID, Long> selfVerifyCooldown = new ConcurrentHashMap<>();

    // -- IP-level protection ------------------------------------------------------------
    // Counted on demand from live sessions + retry-waiting players (no extra bookkeeping).
    private int maxPendingPerIp;

    // -- Shutdown flag (prevents RETRY/KICK during onDisable) -----------------------
    private volatile boolean shuttingDown;

    /** Floor for escalation-reduced timeouts (seconds). */
    private static final long MIN_ESCALATED_TIMEOUT_SECONDS = 15L;

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
                retryPending.add(player.getUniqueId());
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
        mathChallenges.clear();
        retryPending.clear();
        clickWindows.clear();
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

        // v5 defaults
        if (!cfg.isSet("config-version")) {
            cfg.set("config-version", 5);
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
        changed |= setDefault(cfg, "retry-delay-ticks", 20L);
        changed |= setDefault(cfg, "math-max-sum", 20);
        changed |= setDefault(cfg, "math-option-count", 4);
        // v5: MEMORY / REACTION modes
        changed |= setDefault(cfg, "memory-count", 3);
        changed |= setDefault(cfg, "memory-show-ticks", 60L);
        changed |= setDefault(cfg, "reaction-base-ticks", 40L);
        changed |= setDefault(cfg, "reaction-extra-ticks", 60L);
        // v4: difficulty escalation (harder puzzles after consecutive failures)
        changed |= setDefault(cfg, "difficulty-escalation", true);
        changed |= setDefault(cfg, "escalation-fail-step", 2);
        changed |= setDefault(cfg, "escalation-max-level", 3);
        changed |= setDefault(cfg, "escalation-timeout-penalty", 10L);
        changed |= setDefault(cfg, "escalation-math-bonus", 10);
        // v4: anti-script (click flood)
        changed |= setDefault(cfg, "anti-flood", true);
        changed |= setDefault(cfg, "click-window-ms", 3000L);
        changed |= setDefault(cfg, "click-max-clicks", 12);
        // v4: self-service verify cooldown (seconds)
        changed |= setDefault(cfg, "verify-cooldown-seconds", 10L);
        // v4: max concurrent unverified players per IP (0 = unlimited)
        changed |= setDefault(cfg, "max-pending-per-ip", 3);
        changed |= setDefault(cfg, "fail-kick-message", "&c验证失败次数过多，已被移出服务器。");
        changed |= setDefault(cfg, "expire-kick-message", "&c验证超时，已被移出服务器。");
        changed |= setDefault(cfg, "messages.bypassed", "&a你拥有验证豁免权限，无需验证。");
        changed |= setDefault(cfg, "messages.retry", "&e验证未通过，已为你重新开始验证。");
        changed |= setDefault(cfg, "messages.frozen-chat", "&c验证完成前无法聊天。");
        changed |= setDefault(cfg, "messages.frozen-command", "&c验证完成前无法使用指令。");
        changed |= setDefault(cfg, "messages.instructions-math", "&7计算 &e{question} &7，点击 &a正确答案&7。");
        changed |= setDefault(cfg, "messages.instructions-reverse", "&7请按 &e从大到小&7 的顺序点击目标方块。");
        changed |= setDefault(cfg, "messages.instructions-line", "&7请点击 &a同一行&7 的所有目标方块。");
        changed |= setDefault(cfg, "messages.escalated", "&e检测到多次失败，验证难度已提升至 &c{level} &e级。");
        changed |= setDefault(cfg, "messages.flood", "&c点击过快，请放慢速度！剩余尝试次数：&e{remaining}");
        changed |= setDefault(cfg, "messages.cooldown", "&c请 &e{seconds} &c秒后再重新验证。");
        changed |= setDefault(cfg, "messages.ip-limit", "&c当前网络下待验证人数过多，请稍后再试。");
        // v5: MEMORY / REACTION messages
        changed |= setDefault(cfg, "messages.instructions-memory", "&7记住 &e{count} &7个发光方块的位置！");
        changed |= setDefault(cfg, "messages.instructions-memory-recall", "&7点击刚才发光的 &a{count} &7个方块。");
        changed |= setDefault(cfg, "messages.instructions-reaction", "&7等待按钮变 &a绿色&7 后立刻点击！提前点击算失败。");
        changed |= setDefault(cfg, "messages.too-soon", "&c太心急了！等按钮变绿再点。剩余尝试次数：&e{remaining}");

        if (changed) {
            cfg.set("config-version", 5);
            saveConfig();
            getLogger().info("Configuration migrated to version 5 (new defaults written).");
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

        // MATH options
        mathMaxSum     = Math.max(4, cfg.getInt("math-max-sum", 20));
        mathOptionCount = Math.max(2, Math.min(9, cfg.getInt("math-option-count", 4)));

        // MEMORY / REACTION options
        memoryCount     = Math.max(1, Math.min(9, cfg.getInt("memory-count", 3)));
        memoryShowTicks = Math.max(10L, cfg.getLong("memory-show-ticks", 60L));
        reactionBaseTicks  = Math.max(10L, cfg.getLong("reaction-base-ticks", 40L));
        reactionExtraTicks = Math.max(0L, cfg.getLong("reaction-extra-ticks", 60L));

        // Difficulty escalation
        difficultyEscalation = cfg.getBoolean("difficulty-escalation", true);
        escalationStep       = Math.max(1, cfg.getInt("escalation-fail-step", 2));
        escalationMaxLevel   = Math.max(0, cfg.getInt("escalation-max-level", 3));
        escalationTimeoutPenalty = Math.max(0L, cfg.getLong("escalation-timeout-penalty", 10L));
        escalationMathBonus  = Math.max(0, cfg.getInt("escalation-math-bonus", 10));

        // Anti-script (click flood)
        antiFlood      = cfg.getBoolean("anti-flood", true);
        clickWindowMs  = Math.max(500L, cfg.getLong("click-window-ms", 3000L));
        clickMaxClicks = Math.max(3, cfg.getInt("click-max-clicks", 12));

        // Self-service verify cooldown
        verifyCooldownSeconds = Math.max(0L, cfg.getLong("verify-cooldown-seconds", 10L));

        // IP-level protection (0 = unlimited)
        maxPendingPerIp = Math.max(0, cfg.getInt("max-pending-per-ip", 3));

        // Enforcement
        freezeUnverified = cfg.getBoolean("freeze-unverified", true);
        freezeMovement = cfg.getBoolean("freeze-movement", true);
        freezeInteract = cfg.getBoolean("freeze-interact", true);
        freezeChat     = cfg.getBoolean("freeze-chat", true);
        freezeCommands = cfg.getBoolean("freeze-commands", true);
        commandWhitelist = List.copyOf(cfg.getStringList("command-whitelist"));

        // Fail / expire
        failAction   = parseAction(cfg.getString("fail-action", "RETRY"));
        expireAction = parseAction(cfg.getString("expire-action", "RETRY"));
        retryDelayTicks = normalizeRetryDelay(cfg.getLong("retry-delay-ticks", 20L));
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

    /** Freeze is active only when both the global toggle and the per-action toggle are on. */
    static boolean freezeActive(boolean globalFreeze, boolean actionFreeze) {
        return globalFreeze && actionFreeze;
    }

    /** Retry delay in ticks, clamped to >= 1. */
    static long normalizeRetryDelay(long configured) {
        return Math.max(1L, configured);
    }

    /** Verification timeout in ticks: config seconds clamped to >= 5, converted at 20 ticks/s. */
    static long timeoutTicks(long timeoutSeconds) {
        return Math.max(1L, Duration.ofSeconds(Math.max(5L, timeoutSeconds)).toSeconds() * 20L);
    }

    /**
     * Escalation level from consecutive failures: every {@code failStep} failures
     * raises one level, capped at {@code maxLevel}. Non-positive config disables.
     */
    static int escalationLevel(int consecutiveFailures, int failStep, int maxLevel) {
        if (failStep <= 0 || maxLevel <= 0 || consecutiveFailures <= 0) return 0;
        return Math.min(maxLevel, consecutiveFailures / failStep);
    }

    /** SEQUENCE/REVERSE length under escalation (+1 per level, capped by grid). */
    static int escalatedSequenceLength(int base, int level, int size) {
        return Math.max(2, Math.min(size, base + Math.max(0, level)));
    }

    /** COUNT/LINE target count under escalation (+1 per level, capped). */
    static int escalatedTargetCount(int base, int level, int size) {
        return Math.max(1, Math.min(size - 1, base + Math.max(0, level)));
    }

    /** MATH max-sum under escalation (+bonus per level). */
    static int escalatedMathMaxSum(int base, int bonusPerLevel, int level) {
        return Math.max(4, base + Math.max(0, bonusPerLevel) * Math.max(0, level));
    }

    /** Timeout seconds under escalation (-penalty per level, floor 15s). */
    static long escalatedTimeoutSeconds(long base, long penaltyPerLevel, int level) {
        return Math.max(MIN_ESCALATED_TIMEOUT_SECONDS, base - Math.max(0L, penaltyPerLevel) * Math.max(0, level));
    }

    /**
     * Sliding-window flood check. Records {@code nowMs} and returns true when
     * more than {@code maxClicks} clicks fall inside the last {@code windowMs}.
     * The deque is mutated in place (expired entries pruned).
     */
    static boolean recordClickAndCheckFlood(Deque<Long> window, long nowMs, long windowMs, int maxClicks) {
        window.addLast(nowMs);
        long cutoff = nowMs - Math.max(1L, windowMs);
        while (!window.isEmpty() && window.peekFirst() < cutoff) window.pollFirst();
        return window.size() > Math.max(1, maxClicks);
    }

    /** Remaining self-verify cooldown in seconds (0 = may proceed). */
    static long verifyCooldownRemaining(long lastUseMillis, long nowMillis, long cooldownSeconds) {
        if (cooldownSeconds <= 0) return 0;
        long elapsed = (nowMillis - lastUseMillis) / 1000L;
        return Math.max(0L, cooldownSeconds - elapsed);
    }

    /**
     * Drop cooldown entries long past their TTL. Returns the removed count.
     * Called lazily when the map grows large so memory stays bounded on
     * busy servers (every self-verify use adds one entry).
     */
    static int purgeExpiredCooldowns(Map<UUID, Long> map, long nowMillis, long cooldownSeconds) {
        long ttlMs = Math.max(0L, cooldownSeconds) * 1000L;
        int removed = 0;
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMillis - it.next().getValue() > ttlMs) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** True when the IP already hosts {@code maxPerIp} pending players (0 = unlimited). */
    static boolean ipLimitReached(int pendingOnIp, int maxPerIp) {
        if (maxPerIp <= 0) return false;
        return pendingOnIp >= maxPerIp;
    }

    /** IP string of an online player, or null when unavailable. */
    static String ipOf(Player player) {
        if (player == null) return null;
        var socket = player.getAddress();
        if (socket == null || socket.getAddress() == null) return null;
        return socket.getAddress().getHostAddress();
    }

    /**
     * Recheck an IP-limited player after a delay. The first refusal notifies
     * the player; rechecks stay silent until the challenge finally opens.
     */
    private void scheduleIpLimitRecheck(Player player) {
        scheduleForPlayer(player, () -> {
            if (!player.isOnline() || isVerified(player)
                    || sessions.get(player.getUniqueId()) != null) {
                return;
            }
            String ip = ipOf(player);
            if (ip != null && ipLimitReached(countPendingOnIp(ip, player.getUniqueId()), maxPendingPerIp)) {
                scheduleIpLimitRecheck(player);
                return;
            }
            retryPending.remove(player.getUniqueId());
            requestVerification(player);
        }, Math.max(retryDelayTicks, 100L));
    }

    /** Count players (excluding one UUID) currently awaiting verification from an IP. */
    int countPendingOnIp(String ip, UUID exclude) {
        int count = 0;
        for (UUID id : sessions.keySet()) {
            if (id.equals(exclude)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && ip.equals(ipOf(p))) count++;
        }
        for (UUID id : retryPending) {
            if (id.equals(exclude)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && ip.equals(ipOf(p))) count++;
        }
        return count;
    }

    /** One row of slots for LINE mode; row is clamped into range. */
    static List<Integer> lineSlots(int size, int rows, int row) {
        int safeRow = Math.max(0, Math.min(rows - 1, row));
        List<Integer> slots = new ArrayList<>();
        for (int col = 0; col < 9; col++) slots.add(safeRow * 9 + col);
        return List.copyOf(slots);
    }

    /** Descending-order target slots for REVERSE mode (largest first). */
    static List<Integer> reverseSlots(List<Integer> drawn, int sequenceLength) {
        List<Integer> ordered = new ArrayList<>(drawn.subList(0, sequenceLength));
        ordered.sort(Collections.reverseOrder());
        return List.copyOf(ordered);
    }

    /** Build a MATH challenge: a+b question plus distinct answer options containing the sum. */
    static MathChallenge buildMathChallenge(int maxSum, int optionCount, java.util.Random rng) {
        int safeMax = Math.max(4, maxSum);
        int safeOptions = Math.max(2, Math.min(9, optionCount));
        int a = 1 + rng.nextInt(Math.max(1, safeMax - 1));
        int b = 1 + rng.nextInt(Math.max(1, safeMax - a));
        int answer = a + b;
        LinkedHashSet<Integer> options = new LinkedHashSet<>();
        options.add(answer);
        int guard = 0;
        while (options.size() < safeOptions && guard++ < 200) {
            int wrong = 1 + rng.nextInt(safeMax + 3);
            if (wrong != answer) options.add(wrong);
        }
        return new MathChallenge(a + " + " + b + " = ?", answer, List.copyOf(options));
    }

    /** Immutable MATH challenge data shared with the GUI renderer. */
    record MathChallenge(String question, int answer, List<Integer> options) { }

    /** MEMORY phase-1 slots: distinct random slots, sorted for stable rendering. */
    static List<Integer> memorySlots(List<Integer> shuffled, int count) {
        int safe = Math.max(1, Math.min(shuffled.size(), count));
        List<Integer> picked = new ArrayList<>(shuffled.subList(0, safe));
        Collections.sort(picked);
        return List.copyOf(picked);
    }

    /** REACTION delay in ticks: base + up to extra, drawn from rng. */
    static long reactionDelayTicks(long baseTicks, long extraTicks, java.util.Random rng) {
        return Math.max(1L, baseTicks) + (extraTicks > 0 ? rng.nextLong(extraTicks + 1) : 0L);
    }

    /**
     * Whitelist check for frozen commands. Matches on the command word only:
     * "/login" matches "/login" and "/login <args>" but NOT "/loginfoo".
     * Entries may be written with or without a leading slash.
     */
    public static boolean isWhitelistedCommand(String message, List<String> whitelist) {
        if (message == null || whitelist == null) return false;
        String input = message.trim().toLowerCase(Locale.ROOT);
        if (!input.startsWith("/")) return false;
        String commandWord = input.split("\\s+", 2)[0];
        for (String allowed : whitelist) {
            String entry = allowed.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (!entry.startsWith("/")) entry = "/" + entry;
            if (commandWord.equals(entry)) return true;
        }
        return false;
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
    @Override
    public boolean isPendingVerification(UUID playerId) {
        if (playerId == null) return false;
        return (sessions.containsKey(playerId) || retryPending.contains(playerId)) && !verified.contains(playerId);
    }

    /** Returns true when the player has an active (unfinished) session and is not yet verified. */
    @Override
    public boolean isPendingVerification(Player player) {
        if (player == null) return false;
        return isPendingVerification(player.getUniqueId());
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
            retryPending.remove(player.getUniqueId());
            already.complete(VerificationResult.SUCCESS);
            // Fire event for consistency (bypass / already-verified fast path)
            scheduleForPlayer(player, () ->
                    Bukkit.getPluginManager().callEvent(new HumanVerifyEvent(player, VerificationResult.SUCCESS)), 1L);
            return already;
        }
        if (force) verified.remove(player.getUniqueId());

        if (!force) {
            // Idempotent reuse: an active session must NOT be replaced here.
            // Otherwise a player with 2/3 wrong attempts could just run
            // /humanverify verify to get a fresh puzzle with full attempts.
            CaptchaSession active = sessions.get(player.getUniqueId());
            if (active != null && !active.isCompleted()) {
                retryPending.remove(player.getUniqueId());
                scheduleForPlayer(player, () -> {
                    CaptchaSession current = sessions.get(player.getUniqueId());
                    if (current == active && !active.isCompleted() && player.isOnline()) {
                        player.openInventory(active.getHolder().getInventory());
                    }
                }, 0L);
                return active.getFuture();
            }
        }

        // Cancel any existing session
        CaptchaSession previous = sessions.remove(player.getUniqueId());
        if (previous != null) {
            finish(previous, VerificationResult.CANCELLED, false);
        }

        // IP-level protection: refuse new challenges when too many players
        // from the same address are already awaiting verification.
        // Admin-forced verifications bypass this check.
        // Refused players stay frozen (retryPending) and are retried later.
        if (!force) {
            String ip = ipOf(player);
            if (ip != null && ipLimitReached(countPendingOnIp(ip, player.getUniqueId()), maxPendingPerIp)) {
                retryPending.add(player.getUniqueId());
                player.sendMessage(message("ip-limit"));
                scheduleIpLimitRecheck(player);
                already.complete(VerificationResult.CANCELLED);
                return already;
            }
        }

        int size = normalizedSize(getConfig().getInt("challenge-size", 27));
        int maxAttempts = Math.max(1, getConfig().getInt("max-attempts", 3));
        CaptchaHolder holder = new CaptchaHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, size, titleComponent());
        holder.setInventory(inventory);

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(i);
        Collections.shuffle(slots);

        // Difficulty escalation: consecutive failures make the next puzzle harder.
        int fails = consecutiveFailures.getOrDefault(player.getUniqueId(), 0);
        int level = difficultyEscalation
                ? escalationLevel(fails, escalationStep, escalationMaxLevel) : 0;

        ChallengeMode mode = selectMode();
        int sequenceLength = escalatedSequenceLength(
                Math.max(2, Math.min(size, getConfig().getInt("sequence-length", 3))), level, size);
        int targetCount = escalatedTargetCount(
                Math.max(1, Math.min(size - 1, getConfig().getInt("target-count", 3))), level, size);
        int mathSum = escalatedMathMaxSum(mathMaxSum, escalationMathBonus, level);
        int memoryTargets = Math.max(1, Math.min(9, memoryCount + level));
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        MathChallenge mathChallenge = null;

        List<Integer> expectedSlots = switch (mode) {
            case SEQUENCE -> new ArrayList<>(slots.subList(0, sequenceLength));
            case COUNT    -> new ArrayList<>(slots.subList(0, targetCount));
            case CENTER   -> List.of(centerSlot(size));
            case CORNER   -> List.of(cornerSlot(size, rng));
            case REVERSE  -> reverseSlots(slots, sequenceLength);
            case LINE     -> new ArrayList<>(lineSlots(size, size / 9, rng.nextInt(size / 9)));
            case MEMORY   -> memorySlots(slots, memoryTargets);
            case REACTION -> List.of(slots.get(0));
            case MATH     -> {
                mathChallenge = buildMathChallenge(mathSum, mathOptionCount, rng);
                mathChallenges.put(player.getUniqueId(), mathChallenge);
                // The correct slot is the one displaying the answer number.
                int answerPos = mathChallenge.options().indexOf(mathChallenge.answer());
                yield List.of(slots.get(answerPos));
            }
            // COLOR / MATERIAL / ODD_ONE_OUT: single target at slots.get(0)
            // (RANDOM is already resolved by selectMode(); default is a safety fallback)
            case COLOR, MATERIAL, ODD_ONE_OUT -> List.of(slots.get(0));
            default       -> List.of(slots.get(0));
        };

        // MATH options are rendered as numbers; map each option slot to its number.
        Map<Integer, Integer> mathSlotNumbers = new HashMap<>();
        if (mode == ChallengeMode.MATH && mathChallenge != null) {
            List<Integer> options = mathChallenge.options();
            for (int i = 0; i < options.size(); i++) {
                mathSlotNumbers.put(slots.get(i), options.get(i));
            }
        }

        for (int slot : slots) {
            int step = expectedSlots.indexOf(slot);
            inventory.setItem(slot, createButton(mode, step < 0 ? 0 : step + 1,
                    mathSlotNumbers.getOrDefault(slot, 0), mathChallenge));
        }

        CompletableFuture<VerificationResult> future = new CompletableFuture<>();
        CaptchaSession session = new CaptchaSession(player.getUniqueId(), player, holder, mode, expectedSlots, maxAttempts, future);
        retryPending.remove(player.getUniqueId());
        clickWindows.remove(player.getUniqueId());
        sessions.put(player.getUniqueId(), session);

        // MEMORY / REACTION start disarmed (phase 1); others are clickable immediately.
        if (mode == ChallengeMode.MEMORY) {
            session.setArmed(false);
            renderMemoryPhase(inventory, expectedSlots, true);
            player.sendMessage(color(getConfig().getString("messages.instructions-memory",
                    "&7记住 &e{count} &7个发光方块的位置！").replace("{count}", String.valueOf(expectedSlots.size()))));
            scheduleForPlayer(player, () -> {
                CaptchaSession current = sessions.get(player.getUniqueId());
                if (current == session && !session.isCompleted() && player.isOnline()) {
                    renderMemoryPhase(inventory, expectedSlots, false);
                    session.setArmed(true);
                    player.sendMessage(color(getConfig().getString("messages.instructions-memory-recall",
                            "&7点击刚才发光的 &a{count} &7个方块。").replace("{count}", String.valueOf(expectedSlots.size()))));
                }
            }, memoryShowTicks);
        } else if (mode == ChallengeMode.REACTION) {
            session.setArmed(false);
            int goSlot = expectedSlots.get(0);
            renderReactionPhase(inventory, goSlot, false);
            long delay = reactionDelayTicks(reactionBaseTicks, reactionExtraTicks, rng);
            scheduleForPlayer(player, () -> {
                CaptchaSession current = sessions.get(player.getUniqueId());
                if (current == session && !session.isCompleted() && player.isOnline()) {
                    renderReactionPhase(inventory, goSlot, true);
                    session.setArmed(true);
                }
            }, delay);
        }

        if (level > 0) {
            player.sendMessage(message("escalated").replace("{level}", String.valueOf(level)));
        }

        scheduleForPlayer(player, () -> {
            CaptchaSession current = sessions.get(player.getUniqueId());
            if (current != session || session.isCompleted() || !player.isOnline()) return;
            player.openInventory(inventory);
        }, 0L);

        // Timeout (escalation shortens the window)
        long timeoutSeconds = difficultyEscalation
                ? escalatedTimeoutSeconds(getConfig().getLong("timeout-seconds", 60),
                        escalationTimeoutPenalty, level)
                : getConfig().getLong("timeout-seconds", 60);
        long timeoutTicks = timeoutTicks(timeoutSeconds);
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
        retryPending.remove(playerId);
        consecutiveFailures.remove(playerId);
        clickWindows.remove(playerId);
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

    public boolean isFreezeEnabled() { return freezeUnverified; }
    public boolean isFreezeMovement() { return freezeActive(freezeUnverified, freezeMovement); }
    public boolean isFreezeInteract() { return freezeActive(freezeUnverified, freezeInteract); }
    public boolean isFreezeChat() { return freezeActive(freezeUnverified, freezeChat); }
    public boolean isFreezeCommands() { return freezeActive(freezeUnverified, freezeCommands); }
    public List<String> getCommandWhitelist() { return List.copyOf(commandWhitelist); }

    /**
     * Self-service verify cooldown check. Returns the remaining seconds when the
     * player must wait, or 0 when they may proceed (and records this use).
     * Bypass/admin-forced paths do not call this.
     */
    public long checkSelfVerifyCooldown(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        // Amortized cleanup: every self-verify adds one entry, so purge expired
        // ones once the map grows large to keep memory bounded on busy servers.
        if (selfVerifyCooldown.size() > 512) {
            purgeExpiredCooldowns(selfVerifyCooldown, now, verifyCooldownSeconds);
        }
        long remaining = verifyCooldownRemaining(
                selfVerifyCooldown.getOrDefault(id, 0L), now, verifyCooldownSeconds);
        if (remaining <= 0) {
            selfVerifyCooldown.put(id, now);
        }
        return remaining;
    }

    // ================================================================================
    //  Event handlers (Listener)
    // ================================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("auto-verify-on-join", true)) {
            // Freeze immediately; the join message + GUI open happen 1 tick later.
            retryPending.add(event.getPlayer().getUniqueId());
            scheduleForPlayer(event.getPlayer(), () -> beginAutomaticVerification(event.getPlayer()), 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CaptchaHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.getPlayerId().equals(player.getUniqueId())) return;
        if (event.isShiftClick()) return; // shift-click would move GUI buttons into player inventory
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getHolder() != holder || session.isCompleted()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        // --- MEMORY phase 1 (memorize): clicks are fully ignored (not even flood-counted) ---
        if (session.getMode() == ChallengeMode.MEMORY && !session.isArmed()) {
            return;
        }

        // --- Anti-script: click flood consumes an attempt ---
        if (antiFlood) {
            Deque<Long> window = clickWindows.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            if (recordClickAndCheckFlood(window, System.currentTimeMillis(), clickWindowMs, clickMaxClicks)) {
                int attempts = session.registerWrongAttempt();
                player.sendMessage(message("flood").replace("{remaining}", String.valueOf(session.getRemainingAttempts())));
                if (attempts >= session.getMaxAttempts()) {
                    player.sendMessage(message("failed"));
                    handleTerminal(session, VerificationResult.FAILED);
                }
                return;
            }
        }

        // --- REACTION waiting: any click is too soon and consumes an attempt ---
        if (session.getMode() == ChallengeMode.REACTION && !session.isArmed()) {
            int attempts = session.registerWrongAttempt();
            player.sendMessage(message("too-soon").replace("{remaining}", String.valueOf(session.getRemainingAttempts())));
            if (attempts >= session.getMaxAttempts()) {
                player.sendMessage(message("failed"));
                handleTerminal(session, VerificationResult.FAILED);
            }
            return;
        }

        // --- COUNT / LINE / MEMORY(recall) modes: arbitrary order ---
        if ((session.getMode() == ChallengeMode.COUNT || session.getMode() == ChallengeMode.LINE
                || session.getMode() == ChallengeMode.MEMORY) && session.isExpectedSlot(slot)) {
            if (session.advance(slot)) {
                verified.add(player.getUniqueId());
                consecutiveFailures.remove(player.getUniqueId());
                clickWindows.remove(player.getUniqueId());
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

        // --- SEQUENCE / REVERSE / single-target modes: strict order ---
        // (COUNT / LINE / MEMORY are handled above; they must not fall through here,
        // otherwise a duplicate click could advance progress without selecting.)
        if (session.getMode() != ChallengeMode.COUNT && session.getMode() != ChallengeMode.LINE
                && session.getMode() != ChallengeMode.MEMORY
                && slot == session.getExpectedSlot()) {
            if (session.advance()) {
                verified.add(player.getUniqueId());
                consecutiveFailures.remove(player.getUniqueId());
                clickWindows.remove(player.getUniqueId());
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
        if (session.getMode() != ChallengeMode.SEQUENCE && session.getMode() != ChallengeMode.REVERSE
                && session.getMode() != ChallengeMode.COUNT && session.getMode() != ChallengeMode.LINE
                && session.getMode() != ChallengeMode.MEMORY) {
            event.getView().getTopInventory().setItem(slot, createWrongButton());
        }
        player.sendMessage(message("wrong").replace("{remaining}", String.valueOf(session.getRemainingAttempts())));
        if (attempts >= session.getMaxAttempts()) {
            player.sendMessage(message("failed"));
            handleTerminal(session, VerificationResult.FAILED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        // Dragging across the verification GUI would overlay/replace challenge buttons:
        // cancel any drag that touches the captcha inventory.
        if (!(event.getView().getTopInventory().getHolder() instanceof CaptchaHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.getPlayerId().equals(player.getUniqueId())) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
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
        UUID id = event.getPlayer().getUniqueId();
        CaptchaSession session = sessions.remove(id);
        if (session != null) finish(session, VerificationResult.CANCELLED, false);
        retryPending.remove(id);
        clickWindows.remove(id);
        verified.remove(id);
        // NOTE: consecutiveFailures and selfVerifyCooldown intentionally survive
        // reconnects so quitting cannot reset difficulty or dodge the cooldown.
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
        clickWindows.remove(session.getPlayerId());

        // Track consecutive failures for difficulty escalation.
        // SUCCESS clears the counter at the click handlers / markVerified.
        // KICK and quit intentionally keep it: difficulty survives rejoin
        // so kicking or relogging cannot dodge escalation (see onQuit).
        if (result == VerificationResult.FAILED || result == VerificationResult.EXPIRED) {
            consecutiveFailures.merge(session.getPlayerId(), 1, Integer::sum);
        }

        if (!player.isOnline()) return;

        if (action == FailAction.KICK) {
            String kickMsg = (result == VerificationResult.FAILED)
                    ? getConfig().getString("fail-kick-message", "&c验证失败次数过多，已被移出服务器。")
                    : getConfig().getString("expire-kick-message", "&c验证超时，已被移出服务器。");
            kickPlayer(player, kickMsg);
        } else {
            // RETRY: re-open verification after a short delay.
            // Mark pending immediately so freezing stays active during the delay.
            // Non-force call: if a session was created meanwhile (e.g. admin
            // command), it is reused instead of being replaced.
            retryPending.add(player.getUniqueId());
            player.sendMessage(message("retry"));
            scheduleForPlayer(player, () -> {
                if (player.isOnline() && !isVerified(player)) {
                    requestVerification(player);
                } else {
                    retryPending.remove(player.getUniqueId());
                }
            }, retryDelayTicks);
        }
    }

    /** Kick via EntityScheduler (Folia-safe). */
    private void kickPlayer(Player player, String rawMessage) {
        String colored = color(rawMessage);
        Component kickComponent = LegacyComponentSerializer.legacySection().deserialize(colored);
        // Keep the player frozen until the kick lands (1 tick later).
        retryPending.add(player.getUniqueId());
        scheduleForPlayer(player, () -> {
            retryPending.remove(player.getUniqueId());
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
        mathChallenges.remove(session.getPlayerId());

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
        return createButton(mode, step, 0, null);
    }

    private ItemStack createButton(ChallengeMode mode, int step, int mathNumber, MathChallenge mathChallenge) {
        boolean target = step > 0;
        Material material = switch (mode) {
            case MATERIAL     -> target ? targetMaterial : buttonMaterial;
            case SEQUENCE     -> target ? sequenceMaterial : buttonMaterial;
            case REVERSE      -> target ? sequenceMaterial : buttonMaterial;
            case COUNT        -> target ? countMaterial : buttonMaterial;
            case LINE         -> target ? countMaterial : buttonMaterial;
            case MATH         -> buttonMaterial;
            case ODD_ONE_OUT  -> target ? oddOneOutTargetMaterial : oddOneOutMaterial;
            case CENTER, CORNER -> target ? correctMaterial : buttonMaterial;
            case COLOR        -> target ? correctMaterial : buttonMaterial;
            default -> buttonMaterial; // RANDOM is resolved before reaching here
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name;
            if (mode == ChallengeMode.MATH) {
                // Option slots show their number; filler slots show the button label.
                name = mathNumber > 0
                        ? ChatColor.YELLOW + String.valueOf(mathNumber)
                        : ChatColor.WHITE + "验证按钮";
            } else {
                name = target
                        ? (mode == ChallengeMode.SEQUENCE || mode == ChallengeMode.REVERSE
                            || mode == ChallengeMode.COUNT || mode == ChallengeMode.LINE
                            ? ChatColor.YELLOW + (mode == ChallengeMode.COUNT || mode == ChallengeMode.LINE
                                ? "点击目标 " : "验证步骤 ") + step
                            : ChatColor.GREEN + "点击这里")
                        : ChatColor.WHITE + "验证按钮";
            }
            meta.displayName(legacy(name));
            meta.lore(List.of(legacy(instructions(mode, mathChallenge))));
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

    /**
     * MEMORY phase rendering. Phase 1 ({@code show=true}): targets glow green
     * ("记住这个！"), everything else is a plain button. Phase 2: all slots
     * reset to plain buttons, the player must recall the glowing positions.
     */
    private void renderMemoryPhase(Inventory inventory, List<Integer> targets, boolean show) {
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack item = new ItemStack(show && targets.contains(slot) ? correctMaterial : buttonMaterial);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (show && targets.contains(slot)) {
                    meta.displayName(legacy(ChatColor.GREEN + "记住这个！"));
                } else {
                    meta.displayName(legacy(ChatColor.WHITE + "验证按钮"));
                }
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }
    }

    /**
     * REACTION rendering. Waiting: the slot is red ("等待…").
     * Go: the same slot turns green ("点我！").
     */
    private void renderReactionPhase(Inventory inventory, int goSlot, boolean go) {
        ItemStack item = new ItemStack(go ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(go ? ChatColor.GREEN + "点我！" : ChatColor.RED + "等待…"));
            meta.lore(List.of(legacy(color(getConfig().getString(
                    "messages.instructions-reaction", "&7等待按钮变 &a绿色&7 后立刻点击！提前点击算失败。")))));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        inventory.setItem(goSlot, item);
    }

    // ================================================================================
    //  Messages & utilities
    // ================================================================================

    private ChallengeMode selectMode() {
        if (configuredMode != ChallengeMode.RANDOM) return configuredMode;
        return enabledModes.get(ThreadLocalRandom.current().nextInt(enabledModes.size()));
    }

    private String instructions(ChallengeMode mode) {
        return instructions(mode, null);
    }

    private String instructions(ChallengeMode mode, MathChallenge mathChallenge) {
        String key = "messages.instructions-" + mode.getConfigKey();
        String value = getConfig().getString(key);
        if (value == null) value = getConfig().getString("messages.instructions", "");
        if (mode == ChallengeMode.MATH && mathChallenge != null) {
            value = value.replace("{question}", mathChallenge.question());
        }
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
        } else {
            retryPending.remove(player.getUniqueId());
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
