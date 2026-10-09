package org.cubexmc.humanverify;

import org.cubexmc.humanverify.core.ChallengeMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VerifyUtilsTest {

    // --- normalizedSize ---

    @Test
    void normalizedSizeClampsToNineRange() {
        assertEquals(9, HumanVerifyPlugin.normalizedSize(0));
        assertEquals(9, HumanVerifyPlugin.normalizedSize(1));
        assertEquals(9, HumanVerifyPlugin.normalizedSize(8));
        assertEquals(9, HumanVerifyPlugin.normalizedSize(9));
        assertEquals(9, HumanVerifyPlugin.normalizedSize(10)); // 10 - 10%9 = 9
        assertEquals(27, HumanVerifyPlugin.normalizedSize(27));
        assertEquals(27, HumanVerifyPlugin.normalizedSize(28));
        assertEquals(36, HumanVerifyPlugin.normalizedSize(36));
        assertEquals(54, HumanVerifyPlugin.normalizedSize(54));
        assertEquals(54, HumanVerifyPlugin.normalizedSize(100));
    }

    @Test
    void normalizedSizeAlwaysMultipleOfNine() {
        for (int i = 0; i <= 100; i++) {
            int size = HumanVerifyPlugin.normalizedSize(i);
            assertEquals(0, size % 9, "size " + size + " (input " + i + ") not multiple of 9");
        }
    }

    // --- centerSlot ---

    @Test
    void centerSlotFor27() {
        // 3 rows → row 1 center = 1*9+4 = 13
        assertEquals(13, HumanVerifyPlugin.centerSlot(27));
    }

    @Test
    void centerSlotFor36() {
        // 4 rows → (4/2)*9+4 = 2*9+4 = 22
        assertEquals(22, HumanVerifyPlugin.centerSlot(36));
    }

    @Test
    void centerSlotFor54() {
        // 6 rows → (6/2)*9+4 = 3*9+4 = 31
        assertEquals(31, HumanVerifyPlugin.centerSlot(54));
    }

    @Test
    void centerSlotFor9() {
        // 1 row → row 0 center = 4
        assertEquals(4, HumanVerifyPlugin.centerSlot(9));
    }

    // --- cornerSlot ---

    @Test
    void cornerSlotAlwaysOneOfFourCorners() {
        Random rng = new Random(42);
        int size = 27;
        int rows = size / 9;
        int[] expected = {0, 8, (rows - 1) * 9, size - 1};
        for (int i = 0; i < 100; i++) {
            int slot = HumanVerifyPlugin.cornerSlot(size, rng);
            boolean found = false;
            for (int c : expected) if (c == slot) found = true;
            assertTrue(found, "slot " + slot + " not a corner");
        }
    }

    // --- parseMode ---

    @Test
    void parseModeValid() {
        assertEquals(ChallengeMode.COLOR, HumanVerifyPlugin.parseMode("COLOR"));
        assertEquals(ChallengeMode.MATERIAL, HumanVerifyPlugin.parseMode("material"));
        assertEquals(ChallengeMode.SEQUENCE, HumanVerifyPlugin.parseMode(" SEQUENCE "));
        assertEquals(ChallengeMode.RANDOM, HumanVerifyPlugin.parseMode("RANDOM"));
    }

    @Test
    void parseModeFallbackToRandom() {
        assertEquals(ChallengeMode.RANDOM, HumanVerifyPlugin.parseMode(null));
        assertEquals(ChallengeMode.RANDOM, HumanVerifyPlugin.parseMode(""));
        assertEquals(ChallengeMode.RANDOM, HumanVerifyPlugin.parseMode("UNKNOWN"));
    }

    // --- parseAction ---

    @Test
    void parseActionValid() {
        assertEquals(HumanVerifyPlugin.FailAction.RETRY, HumanVerifyPlugin.parseAction("RETRY"));
        assertEquals(HumanVerifyPlugin.FailAction.KICK, HumanVerifyPlugin.parseAction("kick"));
        assertEquals(HumanVerifyPlugin.FailAction.KICK, HumanVerifyPlugin.parseAction(" Kick "));
    }

    @Test
    void parseActionFallbackToRetry() {
        assertEquals(HumanVerifyPlugin.FailAction.RETRY, HumanVerifyPlugin.parseAction(null));
        assertEquals(HumanVerifyPlugin.FailAction.RETRY, HumanVerifyPlugin.parseAction(""));
        assertEquals(HumanVerifyPlugin.FailAction.RETRY, HumanVerifyPlugin.parseAction("BOGUS"));
    }

    // --- resolveEnabledModes ---

    @Test
    void resolveEnabledModesFiltersRandom() {
        List<String> raw = List.of("COLOR", "MATERIAL", "RANDOM", "SEQUENCE");
        List<ChallengeMode> modes = HumanVerifyPlugin.resolveEnabledModes(raw);
        assertEquals(3, modes.size());
        assertTrue(modes.contains(ChallengeMode.COLOR));
        assertTrue(modes.contains(ChallengeMode.MATERIAL));
        assertTrue(modes.contains(ChallengeMode.SEQUENCE));
        assertFalse(modes.contains(ChallengeMode.RANDOM));
    }

    @Test
    void resolveEnabledModesEmptyFallsBackToColor() {
        List<ChallengeMode> modes = HumanVerifyPlugin.resolveEnabledModes(List.of());
        assertEquals(List.of(ChallengeMode.COLOR), modes);
    }

    @Test
    void resolveEnabledModesDeduplicates() {
        List<String> raw = List.of("COLOR", "color", "COLOR");
        List<ChallengeMode> modes = HumanVerifyPlugin.resolveEnabledModes(raw);
        assertEquals(1, modes.size());
        assertEquals(ChallengeMode.COLOR, modes.get(0));
    }

    // --- freezeActive ---

    @Test
    void freezeActiveRequiresBothToggles() {
        assertTrue(HumanVerifyPlugin.freezeActive(true, true));
        assertFalse(HumanVerifyPlugin.freezeActive(true, false));
        assertFalse(HumanVerifyPlugin.freezeActive(false, true));
        assertFalse(HumanVerifyPlugin.freezeActive(false, false));
    }

    // --- normalizeRetryDelay ---

    @Test
    void normalizeRetryDelayClampsToAtLeastOne() {
        assertEquals(1L, HumanVerifyPlugin.normalizeRetryDelay(0L));
        assertEquals(1L, HumanVerifyPlugin.normalizeRetryDelay(-5L));
        assertEquals(20L, HumanVerifyPlugin.normalizeRetryDelay(20L));
        assertEquals(100L, HumanVerifyPlugin.normalizeRetryDelay(100L));
    }

    // --- timeoutTicks ---

    @Test
    void timeoutTicksConvertsSecondsAt20Tps() {
        assertEquals(60L * 20L, HumanVerifyPlugin.timeoutTicks(60L));
        assertEquals(5L * 20L, HumanVerifyPlugin.timeoutTicks(5L));
    }

    @Test
    void timeoutTicksClampsSmallValuesToFiveSeconds() {
        assertEquals(5L * 20L, HumanVerifyPlugin.timeoutTicks(0L));
        assertEquals(5L * 20L, HumanVerifyPlugin.timeoutTicks(-10L));
        assertEquals(5L * 20L, HumanVerifyPlugin.timeoutTicks(4L));
    }

    // --- isWhitelistedCommand ---

    @Test
    void whitelistMatchesExactCommandWord() {
        List<String> whitelist = List.of("/login", "/register");
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/login", whitelist));
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/login secret123", whitelist));
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/LOGIN", whitelist));
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/register x y", whitelist));
    }

    @Test
    void whitelistRejectsPrefixLookalikes() {
        List<String> whitelist = List.of("/login", "/register");
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/loginfoo", whitelist));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/loginx", whitelist));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/register2", whitelist));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/help", whitelist));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/humaverify verify", whitelist));
    }

    @Test
    void whitelistHandlesEdgeCases() {
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand(null, List.of("/login")));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/login", null));
        assertFalse(HumanVerifyPlugin.isWhitelistedCommand("/login", List.of()));
        // Entry without leading slash still matches
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/login", List.of("login")));
        assertTrue(HumanVerifyPlugin.isWhitelistedCommand("/login foo", List.of("login")));
    }

    // --- lineSlots ---

    @Test
    void lineSlotsReturnsFullRow() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), HumanVerifyPlugin.lineSlots(27, 3, 0));
        assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16, 17), HumanVerifyPlugin.lineSlots(27, 3, 1));
        assertEquals(List.of(18, 19, 20, 21, 22, 23, 24, 25, 26), HumanVerifyPlugin.lineSlots(27, 3, 2));
    }

    @Test
    void lineSlotsClampsRowIntoRange() {
        assertEquals(HumanVerifyPlugin.lineSlots(27, 3, 0), HumanVerifyPlugin.lineSlots(27, 3, -5));
        assertEquals(HumanVerifyPlugin.lineSlots(27, 3, 2), HumanVerifyPlugin.lineSlots(27, 3, 99));
    }

    // --- reverseSlots ---

    @Test
    void reverseSlotsAreDescending() {
        List<Integer> drawn = List.of(5, 40, 13, 27, 9);
        // First 3 drawn [5, 40, 13], sorted descending
        assertEquals(List.of(40, 13, 5), HumanVerifyPlugin.reverseSlots(drawn, 3));
        assertEquals(List.of(40, 27, 13, 9, 5), HumanVerifyPlugin.reverseSlots(drawn, 5));
    }

    // --- buildMathChallenge ---

    @Test
    void mathChallengeAnswerIsInOptions() {
        Random rng = new Random(7);
        for (int i = 0; i < 50; i++) {
            HumanVerifyPlugin.MathChallenge c = HumanVerifyPlugin.buildMathChallenge(20, 4, rng);
            assertTrue(c.options().contains(c.answer()), "answer missing from options");
            assertEquals(4, c.options().size());
            assertEquals(c.options().size(), new java.util.HashSet<>(c.options()).size(), "duplicate options");
        }
    }

    @Test
    void mathChallengeRespectsMaxSum() {
        Random rng = new Random(13);
        for (int i = 0; i < 50; i++) {
            HumanVerifyPlugin.MathChallenge c = HumanVerifyPlugin.buildMathChallenge(10, 3, rng);
            assertTrue(c.answer() >= 2 && c.answer() <= 10, "answer out of range: " + c.answer());
            assertTrue(c.question().contains(" + "), "question format: " + c.question());
        }
    }

    // --- escalationLevel ---

    @Test
    void escalationLevelStepsAndCaps() {
        assertEquals(0, HumanVerifyPlugin.escalationLevel(0, 2, 3));
        assertEquals(0, HumanVerifyPlugin.escalationLevel(1, 2, 3));
        assertEquals(1, HumanVerifyPlugin.escalationLevel(2, 2, 3));
        assertEquals(1, HumanVerifyPlugin.escalationLevel(3, 2, 3));
        assertEquals(3, HumanVerifyPlugin.escalationLevel(6, 2, 3));
        assertEquals(3, HumanVerifyPlugin.escalationLevel(99, 2, 3));
    }

    @Test
    void escalationLevelDisabledConfigs() {
        assertEquals(0, HumanVerifyPlugin.escalationLevel(10, 0, 3));
        assertEquals(0, HumanVerifyPlugin.escalationLevel(10, 2, 0));
        assertEquals(0, HumanVerifyPlugin.escalationLevel(-1, 2, 3));
    }

    @Test
    void escalatedScalingHelpers() {
        assertEquals(4, HumanVerifyPlugin.escalatedSequenceLength(3, 1, 27));
        assertEquals(27, HumanVerifyPlugin.escalatedSequenceLength(26, 5, 27));
        assertEquals(4, HumanVerifyPlugin.escalatedTargetCount(3, 1, 27));
        assertEquals(30, HumanVerifyPlugin.escalatedMathMaxSum(20, 10, 1));
        assertEquals(50L, HumanVerifyPlugin.escalatedTimeoutSeconds(60L, 10L, 1));
        // Floor at 15s
        assertEquals(15L, HumanVerifyPlugin.escalatedTimeoutSeconds(20L, 10L, 5));
    }

    // --- recordClickAndCheckFlood ---

    @Test
    void floodTriggersOverLimitInsideWindow() {
        java.util.Deque<Long> window = new java.util.ArrayDeque<>();
        // 12 clicks inside 3000ms: no flood yet
        for (int i = 0; i < 12; i++) {
            assertFalse(HumanVerifyPlugin.recordClickAndCheckFlood(window, i * 100L, 3000L, 12));
        }
        // 13th click inside the window: flood
        assertTrue(HumanVerifyPlugin.recordClickAndCheckFlood(window, 1200L, 3000L, 12));
    }

    @Test
    void floodWindowSlidesOldClicksOut() {
        java.util.Deque<Long> window = new java.util.ArrayDeque<>();
        for (int i = 0; i < 12; i++) {
            HumanVerifyPlugin.recordClickAndCheckFlood(window, i * 100L, 3000L, 12);
        }
        // Far future click: old entries expire, no flood
        assertFalse(HumanVerifyPlugin.recordClickAndCheckFlood(window, 60_000L, 3000L, 12));
        assertEquals(1, window.size());
    }

    // --- verifyCooldownRemaining ---

    @Test
    void cooldownCountsDown() {
        assertEquals(10L, HumanVerifyPlugin.verifyCooldownRemaining(0L, 0L, 10L));
        assertEquals(5L, HumanVerifyPlugin.verifyCooldownRemaining(0L, 5_000L, 10L));
        assertEquals(0L, HumanVerifyPlugin.verifyCooldownRemaining(0L, 10_000L, 10L));
        assertEquals(0L, HumanVerifyPlugin.verifyCooldownRemaining(0L, 99_000L, 10L));
        assertEquals(0L, HumanVerifyPlugin.verifyCooldownRemaining(0L, 0L, 0L));
    }

    // --- ipLimitReached ---

    @Test
    void ipLimitReachedRespectsLimit() {
        assertFalse(HumanVerifyPlugin.ipLimitReached(2, 3));
        assertTrue(HumanVerifyPlugin.ipLimitReached(3, 3));
        assertTrue(HumanVerifyPlugin.ipLimitReached(9, 3));
        assertFalse(HumanVerifyPlugin.ipLimitReached(99, 0));
    }

    // --- memorySlots ---

    @Test
    void memorySlotsAreDistinctAndSorted() {
        List<Integer> shuffled = List.of(40, 3, 27, 13, 9, 51, 22);
        assertEquals(List.of(3, 27, 40), HumanVerifyPlugin.memorySlots(shuffled, 3));
        assertEquals(List.of(40), HumanVerifyPlugin.memorySlots(shuffled, 1));
        assertEquals(7, HumanVerifyPlugin.memorySlots(shuffled, 99).size());
    }

    // --- reactionDelayTicks ---

    @Test
    void reactionDelayWithinRange() {
        Random rng = new Random(5);
        for (int i = 0; i < 50; i++) {
            long delay = HumanVerifyPlugin.reactionDelayTicks(40L, 60L, rng);
            assertTrue(delay >= 40L && delay <= 100L, "delay out of range: " + delay);
        }
    }

    @Test
    void reactionDelayNoExtraIsBase() {
        assertEquals(40L, HumanVerifyPlugin.reactionDelayTicks(40L, 0L, new Random()));
        assertEquals(1L, HumanVerifyPlugin.reactionDelayTicks(-5L, 0L, new Random()));
    }

    // --- purgeExpiredCooldowns ---

    @Test
    void purgeRemovesOnlyExpiredCooldowns() {
        java.util.Map<java.util.UUID, Long> map = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.UUID fresh = java.util.UUID.randomUUID();
        java.util.UUID expired = java.util.UUID.randomUUID();
        java.util.UUID borderline = java.util.UUID.randomUUID();
        map.put(fresh, 19_500L);     // 0.5s ago, cooldown 10s -> keep
        map.put(expired, 0L);        // long ago -> purge
        map.put(borderline, 5_000L); // 15s ago, past 10s TTL -> purge
        int removed = HumanVerifyPlugin.purgeExpiredCooldowns(map, 20_000L, 10L);
        assertEquals(2, removed);
        assertTrue(map.containsKey(fresh));
        assertFalse(map.containsKey(expired));
    }

    @Test
    void purgeWithZeroCooldownKeepsFreshWrites() {
        java.util.Map<java.util.UUID, Long> map = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.UUID id = java.util.UUID.randomUUID();
        map.put(id, 5_000L);
        // Zero cooldown: everything strictly older is dropped, the map stays usable.
        int removed = HumanVerifyPlugin.purgeExpiredCooldowns(map, 5_000L, 0L);
        assertEquals(0, removed);
        assertTrue(map.containsKey(id));
    }
}
