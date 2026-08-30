package org.cubexmc.humanverify.api;

/** The terminal state of a human-verification request. */
public enum VerificationResult {
    SUCCESS,
    FAILED,
    EXPIRED,
    CANCELLED
}
