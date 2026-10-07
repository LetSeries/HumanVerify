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
}
