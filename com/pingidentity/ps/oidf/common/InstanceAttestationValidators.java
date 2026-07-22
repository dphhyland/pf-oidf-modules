/*
 * Registry of InstanceAttestationValidators keyed by format, with per-request selection.
 */
package com.pingidentity.ps.oidf.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jws.JsonWebSignature;

/**
 * Registry of {@link InstanceAttestationValidator}s keyed by format, with per-request selection. The
 * issuance endpoint holds one of these and picks a validator for each request: an explicitly declared
 * format wins; otherwise the format is sniffed from the presented token (a {@code sub} of
 * {@code spiffe://…} is a SPIFFE SVID; a {@code cnf}-bearing JWT is a wallet WIA), defaulting to SPIFFE.
 * Selection only <em>routes</em> — the chosen validator still fully verifies the attestation.
 */
public final class InstanceAttestationValidators {

    private final Map<String, InstanceAttestationValidator> byFormat;

    public InstanceAttestationValidators(List<InstanceAttestationValidator> validators) {
        Map<String, InstanceAttestationValidator> m = new LinkedHashMap<>();
        for (InstanceAttestationValidator v : validators) {
            m.put(v.format(), v);
        }
        this.byFormat = m;
    }

    /** A registry holding only the SPIFFE validator (the default when no wallet trust is configured). */
    public static InstanceAttestationValidators spiffeOnly() {
        return new InstanceAttestationValidators(List.of(new SpiffeInstanceAttestationValidator()));
    }

    public boolean supports(String format) {
        return this.byFormat.containsKey(format);
    }

    /**
     * Selects the validator for a request.
     *
     * @param declaredFormat an explicit {@code instance_attestation_format} (may be null/blank)
     * @param presented      the compact instance attestation (used to sniff the format when undeclared)
     * @throws IssuanceException {@code invalid_request} if no validator handles the resolved format
     */
    public InstanceAttestationValidator select(String declaredFormat, String presented) throws IssuanceException {
        String fmt = (declaredFormat != null && !declaredFormat.isBlank()) ? declaredFormat.trim() : sniff(presented);
        InstanceAttestationValidator v = this.byFormat.get(fmt);
        if (v == null) {
            throw IssuanceException.invalidRequest("unsupported instance attestation format: " + fmt);
        }
        return v;
    }

    /** Best-effort format detection from an unverified token; defaults to SPIFFE. */
    static String sniff(String presented) {
        if (presented == null || presented.isBlank()) {
            return SpiffeInstanceAttestationValidator.FORMAT;
        }
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(presented);
            JwtClaims claims = JwtClaims.parse(jws.getUnverifiedPayload());
            String sub = claims.getClaimValueAsString("sub");
            if (sub != null && sub.startsWith("spiffe://")) {
                return SpiffeInstanceAttestationValidator.FORMAT;
            }
            String typ = jws.getHeader("typ");
            if (typ != null && WalletInstanceAttestationValidator.WIA_TYPES.contains(typ)) {
                return WalletInstanceAttestationValidator.FORMAT;
            }
            if (claims.hasClaim("cnf")) {
                return WalletInstanceAttestationValidator.FORMAT;
            }
        } catch (Exception ignored) {
            // fall through to the SPIFFE default
        }
        return SpiffeInstanceAttestationValidator.FORMAT;
    }
}
