package com.codex.humanverify.core;

public enum ChallengeMode {
    RANDOM("random"),
    COLOR("color"),
    MATERIAL("material"),
    SEQUENCE("sequence");

    private final String configKey;

    ChallengeMode(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
