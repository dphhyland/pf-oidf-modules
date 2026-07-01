/*
 * Parsed Client Attestation JWT (draft-ietf-oauth-attestation-based-client-auth).
 */
package com.pingidentity.ps.oidf.common;

import java.util.Map;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;

/**
 * Immutable view of a verified Client Attestation JWT. The Attestation is issued by a Client Attester
 * ({@code iss}), names the client ({@code sub} = {@code client_id}) and binds the client instance key
 * via the RFC 7800 {@code cnf} claim ({@code cnf.jwk}). It carries {@code exp} (required) and may carry
 * {@code iat}.
 */
public final class ClientAttestation {
    private final String attesterIssuer;
    private final String clientId;
    private final Map<String, Object> cnfJwk;
    private final long expEpochSeconds;
    private final long iatEpochSeconds;
    private final String raw;

    public ClientAttestation(String attesterIssuer, String clientId, Map<String, Object> cnfJwk, long expEpochSeconds, long iatEpochSeconds, String raw) {
        this.attesterIssuer = attesterIssuer;
        this.clientId = clientId;
        this.cnfJwk = cnfJwk;
        this.expEpochSeconds = expEpochSeconds;
        this.iatEpochSeconds = iatEpochSeconds;
        this.raw = raw;
    }

    /**
     * Builds a {@link ClientAttestation} from already signature-verified claims, enforcing the presence
     * of {@code sub} and {@code cnf.jwk}. ({@code iss}/{@code exp} are enforced during signature
     * verification by {@link JwtCodec#verifyAgainstKeys}.)
     */
    public static ClientAttestation fromVerifiedClaims(JwtClaims claims, String raw) throws MalformedClaimException {
        String attester = Claims.requireNonBlank(claims.getIssuer(), "iss");
        String clientId = Claims.requireNonBlank(claims.getSubject(), "sub");
        Map<String, Object> cnf = Claims.requiredMap(claims, "cnf");
        Map<String, Object> jwk = Claims.optionalNestedMap(cnf, "jwk");
        if (jwk.isEmpty()) {
            throw new IllegalArgumentException("Client Attestation 'cnf' claim must use the 'jwk' confirmation method");
        }
        long exp = claims.hasClaim("exp") ? claims.getExpirationTime().getValue() : 0L;
        long iat = claims.hasClaim("iat") ? claims.getIssuedAt().getValue() : 0L;
        return new ClientAttestation(attester, clientId, jwk, exp, iat, raw);
    }

    public String attesterIssuer() {
        return this.attesterIssuer;
    }

    public String clientId() {
        return this.clientId;
    }

    public Map<String, Object> cnfJwk() {
        return this.cnfJwk;
    }

    public long expEpochSeconds() {
        return this.expEpochSeconds;
    }

    public long iatEpochSeconds() {
        return this.iatEpochSeconds;
    }

    public String raw() {
        return this.raw;
    }
}
