/*
 * Per-client configuration for the attestation issuance endpoint, parsed from extended properties.
 */
package com.pingidentity.ps.oidf.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

/**
 * Typed view of a client's attestation-issuance configuration, parsed from its {@code attestation_*}
 * extended properties. Holds the attester identity, the SPIFFE trust bundle used to validate SVIDs, the
 * signer selection (OpenBao transit key reference or inline JWK), the issued-attestation TTL, an optional
 * client-level RFC 9396 entitlement ceiling, and the one-to-many list of {@link SpiffeBinding}s.
 *
 * <p>This class is pure data + parsing (no PingFederate types), so it is unit-testable offline. In the
 * runtime a resolver reads the properties off a PF {@code Client} and calls {@link #fromProperties}; the
 * <em>source</em> of the bundle is that resolver's concern (an inline JWKS today, federation metadata
 * later) — the validation and minting downstream never change.
 */
public final class AttestationIssuanceConfig {

    // Extended-property keys (the OGNL hook sees these under the "extproperties." namespace).
    public static final String P_ISSUER = "attestation_issuer";
    public static final String P_TTL = "attestation_issued_ttl";
    public static final String P_BUNDLE = "attestation_spiffe_bundle";
    public static final String P_ENTITLEMENT = "attestation_entitlement";
    public static final String P_SIGNING_KEY_REF = "attestation_signing_key_ref";
    public static final String P_SIGNING_JWK = "attestation_signing_jwk";
    public static final String P_INSTANCES = "attestation_instances";
    public static final String P_TRUST_DOMAIN = "attestation_trust_domain";

    public static final long DEFAULT_TTL_SECONDS = 300L;

    private final String issuer;
    private final long ttlSeconds;
    private final List<JsonWebKey> bundleKeys;
    private final List<Map<String, Object>> clientCeiling;
    private final String signingKeyRef;
    private final Map<String, Object> signingJwk;
    private final String expectedTrustDomain;
    private final List<SpiffeBinding> bindings;

    private AttestationIssuanceConfig(String issuer, long ttlSeconds, List<JsonWebKey> bundleKeys,
                                      List<Map<String, Object>> clientCeiling, String signingKeyRef,
                                      Map<String, Object> signingJwk, String expectedTrustDomain,
                                      List<SpiffeBinding> bindings) {
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
        this.bundleKeys = bundleKeys;
        this.clientCeiling = clientCeiling;
        this.signingKeyRef = signingKeyRef;
        this.signingJwk = signingJwk;
        this.expectedTrustDomain = expectedTrustDomain;
        this.bindings = bindings;
    }

    /**
     * Parses the {@code attestation_*} property map into a config, validating the required shape.
     *
     * @throws IssuanceException {@code invalid_client} if a required property is missing or malformed
     */
    public static AttestationIssuanceConfig fromProperties(Map<String, String> props) throws IssuanceException {
        String issuer = trimmed(props.get(P_ISSUER));
        if (issuer == null) {
            throw IssuanceException.invalidClient("missing " + P_ISSUER);
        }
        long ttl = DEFAULT_TTL_SECONDS;
        String ttlRaw = trimmed(props.get(P_TTL));
        if (ttlRaw != null) {
            try {
                ttl = Long.parseLong(ttlRaw);
            } catch (NumberFormatException e) {
                throw IssuanceException.invalidClient(P_TTL + " is not a number: " + ttlRaw);
            }
            if (ttl <= 0) {
                throw IssuanceException.invalidClient(P_TTL + " must be positive");
            }
        }

        // The SPIFFE trust bundle is optional: a wallet-only client (which proves instances with a Wallet
        // Instance Attestation, not an SVID) configures none. When absent, the bundle is empty and a SPIFFE
        // request to this client fails cleanly at SVID validation; wallet requests never consult it.
        String bundleJson = trimmed(props.get(P_BUNDLE));
        List<JsonWebKey> bundleKeys;
        if (bundleJson == null) {
            bundleKeys = List.of();
        } else {
            try {
                bundleKeys = new JsonWebKeySet(bundleJson).getJsonWebKeys();
            } catch (JoseException e) {
                throw IssuanceException.invalidClient(P_BUNDLE + " is not a valid JWKS");
            }
        }

        List<Map<String, Object>> ceiling = parseAuthDetails(trimmed(props.get(P_ENTITLEMENT)), P_ENTITLEMENT);
        String signingKeyRef = trimmed(props.get(P_SIGNING_KEY_REF));
        Map<String, Object> signingJwk = parseObject(trimmed(props.get(P_SIGNING_JWK)), P_SIGNING_JWK);
        String trustDomain = trimmed(props.get(P_TRUST_DOMAIN));
        List<SpiffeBinding> bindings = parseInstances(trimmed(props.get(P_INSTANCES)), ceiling);

        return new AttestationIssuanceConfig(issuer, ttl, bundleKeys, ceiling, signingKeyRef, signingJwk,
                trustDomain, bindings);
    }

    /**
     * Builds a config from an {@code oauth_client_attestation} metadata block (native maps/lists, as a
     * federation entity statement or a CIMD document carries it). The trust bundle is supplied by the
     * caller — it is the <em>resolver's</em> trust decision (chain-vouched for federation, the attester's
     * configured bundle for CIMD), never read from a self-asserted document here. Metadata-sourced configs
     * carry no private signing key; the signer is resolved by {@code issuer}
     * ({@link AttesterSigningKey#signerForIssuer}).
     *
     * @param att        the {@code oauth_client_attestation} metadata (attester / issued_ttl / entitlement / instances)
     * @param bundleKeys the SPIFFE trust bundle to validate SVIDs against (resolver-supplied, non-empty)
     */
    public static AttestationIssuanceConfig fromEntityMetadata(Map<String, Object> att, List<JsonWebKey> bundleKeys)
            throws IssuanceException {
        if (att == null || att.isEmpty()) {
            throw IssuanceException.invalidClient("oauth_client_attestation metadata is missing");
        }
        String issuer = str(att.get("attester"));
        if (issuer == null) {
            throw IssuanceException.invalidClient("oauth_client_attestation: missing 'attester'");
        }
        long ttl = DEFAULT_TTL_SECONDS;
        Object ttlValue = att.get("issued_ttl");
        if (ttlValue instanceof Number) {
            ttl = ((Number) ttlValue).longValue();
        } else if (ttlValue != null) {
            try {
                ttl = Long.parseLong(String.valueOf(ttlValue).trim());
            } catch (NumberFormatException e) {
                throw IssuanceException.invalidClient("oauth_client_attestation 'issued_ttl' is not a number");
            }
        }
        if (ttl <= 0) {
            throw IssuanceException.invalidClient("oauth_client_attestation 'issued_ttl' must be positive");
        }
        if (bundleKeys == null || bundleKeys.isEmpty()) {
            throw IssuanceException.invalidClient("no SPIFFE trust bundle available for this client");
        }
        List<Map<String, Object>> ceiling = asObjectList(att.get("entitlement"), "entitlement");
        List<SpiffeBinding> bindings = parseInstancesList(asList(att.get("instances"), "instances"), ceiling);
        String trustDomain = str(att.get("trust_domain"));
        return new AttestationIssuanceConfig(issuer, ttl, bundleKeys, ceiling, null, null, trustDomain, bindings);
    }

    private static List<?> asList(Object value, String field) throws IssuanceException {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw IssuanceException.invalidClient("oauth_client_attestation '" + field + "' must be a JSON array");
        }
        return (List<?>) value;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    public String issuer() {
        return this.issuer;
    }

    public long ttlSeconds() {
        return this.ttlSeconds;
    }

    public List<JsonWebKey> bundleKeys() {
        return this.bundleKeys;
    }

    /** The client-level entitlement ceiling; empty if none configured. */
    public List<Map<String, Object>> clientCeiling() {
        return this.clientCeiling;
    }

    public String signingKeyRef() {
        return this.signingKeyRef;
    }

    public Map<String, Object> signingJwk() {
        return this.signingJwk;
    }

    /** The expected SVID trust domain, if the client pins one; else null (any). */
    public String expectedTrustDomain() {
        return this.expectedTrustDomain;
    }

    public List<SpiffeBinding> bindings() {
        return this.bindings;
    }

    /** Finds the binding for a validated SPIFFE ID, or empty if the id is not registered for this client. */
    public Optional<SpiffeBinding> bindingFor(String spiffeId) {
        for (SpiffeBinding b : this.bindings) {
            if (b.spiffeId().equals(spiffeId)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /** The effective entitlement ceiling for a binding: its own if set, else the client-level ceiling. */
    public List<Map<String, Object>> effectiveCeiling(SpiffeBinding binding) {
        return binding.entitlement().isEmpty() ? this.clientCeiling : binding.entitlement();
    }

    private static List<SpiffeBinding> parseInstances(String json, List<Map<String, Object>> clientCeiling)
            throws IssuanceException {
        if (json == null) {
            return List.of();
        }
        Object parsed;
        try {
            parsed = JsonUtil.parseJson("{\"v\":" + json + "}").get("v");
        } catch (JoseException e) {
            throw IssuanceException.invalidClient(P_INSTANCES + " is not valid JSON");
        }
        if (!(parsed instanceof List)) {
            throw IssuanceException.invalidClient(P_INSTANCES + " must be a JSON array");
        }
        return parseInstancesList((List<?>) parsed, clientCeiling);
    }

    /** Builds the {@link SpiffeBinding} list from an already-parsed native array (the shared core). */
    private static List<SpiffeBinding> parseInstancesList(List<?> parsed, List<Map<String, Object>> clientCeiling)
            throws IssuanceException {
        List<SpiffeBinding> out = new ArrayList<>();
        for (Object item : parsed) {
            if (!(item instanceof Map)) {
                throw IssuanceException.invalidClient(P_INSTANCES + " entries must be JSON objects");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;
            // The instance subject: a SPIFFE ID, or a wallet instance id. Accept any of the aliases so a
            // wallet binding reads naturally; they populate the same format-neutral binding subject.
            Object idValue = entry.get("spiffe_id");
            if (idValue == null) {
                idValue = entry.get("subject");
            }
            if (idValue == null) {
                idValue = entry.get("wallet_instance");
            }
            String subject = idValue == null ? null : String.valueOf(idValue).trim();
            if (subject == null || subject.isBlank()) {
                throw IssuanceException.invalidClient(
                        P_INSTANCES + " entry is missing an instance id (spiffe_id / subject / wallet_instance)");
            }
            List<Map<String, Object>> entitlement = asObjectList(entry.get("entitlement"), "entitlement");
            Map<String, Object> metadata = asObject(entry.get("metadata"), "metadata");
            // Defense: a per-instance entitlement must sit within the client-level ceiling.
            if (!entitlement.isEmpty() && clientCeiling != null && !clientCeiling.isEmpty()) {
                try {
                    RarEntitlement.authorize(entitlement, clientCeiling);
                } catch (ClientAttestationException e) {
                    throw IssuanceException.invalidClient(
                            "instance '" + subject + "' entitlement exceeds the client-level ceiling");
                }
            }
            out.add(new SpiffeBinding(subject, entitlement, metadata));
        }
        return out;
    }

    private static List<Map<String, Object>> parseAuthDetails(String json, String field) throws IssuanceException {
        if (json == null) {
            return List.of();
        }
        try {
            return RarEntitlement.parseArray(json);
        } catch (ClientAttestationException e) {
            throw IssuanceException.invalidClient(field + " is not a valid authorization_details array");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asObjectList(Object value, String field) throws IssuanceException {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw IssuanceException.invalidClient("'" + field + "' must be a JSON array");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                throw IssuanceException.invalidClient("'" + field + "' entries must be JSON objects");
            }
            out.add((Map<String, Object>) item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String field) throws IssuanceException {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw IssuanceException.invalidClient("'" + field + "' must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> parseObject(String json, String field) throws IssuanceException {
        if (json == null) {
            return null;
        }
        try {
            return JsonUtil.parseJson(json);
        } catch (JoseException e) {
            throw IssuanceException.invalidClient(field + " is not a valid JSON object");
        }
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
