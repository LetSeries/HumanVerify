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
}
