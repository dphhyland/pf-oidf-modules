/*
 * Successful result of attestation-based client authentication.
 */
package com.pingidentity.ps.oidf.common;

import java.util.Map;

/**
 * Outcome of a successful {@link ClientAttestationVerifier} run: the authenticated {@code client_id},
 * the confirmed instance key ({@code cnf.jwk}), the proof-of-possession mode that was used and the
 * trusted Attester that issued the attestation.
 */
public final class ClientAttestationResult {
    public enum Mode {
        /** Dedicated Client Attestation PoP JWT ({@code attest_jwt_client_auth}). */
        POP_JWT,
        /** DPoP combined mode ({@code attest_jwt_client_auth_dpop}). */
        DPOP
    }

    private final String clientId;
    private final Map<String, Object> cnfJwk;
    private final Mode mode;
    private final String attesterIssuer;
    private final String proofJti;

    public ClientAttestationResult(String clientId, Map<String, Object> cnfJwk, Mode mode, String attesterIssuer, String proofJti) {
        this.clientId = clientId;
        this.cnfJwk = cnfJwk;
        this.mode = mode;
        this.attesterIssuer = attesterIssuer;
        this.proofJti = proofJti;
    }

    public String clientId() {
        return this.clientId;
    }

    public Map<String, Object> cnfJwk() {
        return this.cnfJwk;
    }

    public Mode mode() {
        return this.mode;
    }

    public String attesterIssuer() {
        return this.attesterIssuer;
    }

    public String proofJti() {
        return this.proofJti;
    }
}
