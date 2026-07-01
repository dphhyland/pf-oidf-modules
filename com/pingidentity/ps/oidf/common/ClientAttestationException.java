/*
 * Typed failure for attestation-based client authentication.
 */
package com.pingidentity.ps.oidf.common;

/**
 * Carries the OAuth error code that a failed attestation verification maps to, per
 * draft-ietf-oauth-attestation-based-client-auth Section 7. Most failures are {@code invalid_client};
 * {@code use_attestation_challenge} and {@code use_fresh_attestation} signal that the client should
 * retry with a server-provided challenge / a freshly issued attestation respectively.
 */
public final class ClientAttestationException extends Exception {
    private static final long serialVersionUID = 1L;
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String USE_ATTESTATION_CHALLENGE = "use_attestation_challenge";
    public static final String USE_FRESH_ATTESTATION = "use_fresh_attestation";

    private final String error;

    public ClientAttestationException(String error, String message) {
        super(message);
        this.error = error;
    }

    public ClientAttestationException(String error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public String error() {
        return this.error;
    }

    public static ClientAttestationException invalidClient(String message) {
        return new ClientAttestationException(INVALID_CLIENT, message);
    }

    public static ClientAttestationException invalidClient(String message, Throwable cause) {
        return new ClientAttestationException(INVALID_CLIENT, message, cause);
    }

    public static ClientAttestationException useChallenge(String message) {
        return new ClientAttestationException(USE_ATTESTATION_CHALLENGE, message);
    }

    public static ClientAttestationException useFreshAttestation(String message) {
        return new ClientAttestationException(USE_FRESH_ATTESTATION, message);
    }
}
