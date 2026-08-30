package org.cubexmc.humanverify.core;

public enum ChallengeMode {
    RANDOM("random"),
    COLOR("color"),
    MATERIAL("material"),
    SEQUENCE("sequence"),
    COUNT("count"),
    ODD_ONE_OUT("odd-one-out"),
    CENTER("center"),
    CORNER("corner");

    private final String configKey;

    ChallengeMode(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
