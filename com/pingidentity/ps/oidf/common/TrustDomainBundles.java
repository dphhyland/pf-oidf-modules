/*
 * The attester's configured SPIFFE trust bundles, keyed by trust domain.
 */
package com.pingidentity.ps.oidf.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

/**
 * The SPIFFE trust bundles the attester is configured to trust, keyed by trust domain. Used by the
 * {@link CimdIssuanceClientResolver}: because a CIMD document is <em>unsigned</em>, its SPIFFE bundle must
 * never be self-asserted — the attester supplies the bundle for the SVID's trust domain from here, and a
 * CIMD document may only assert which {@code spiffe_id}s (under a trust domain the attester serves) are its
 * instances. (The federation resolver does not use this — a chain-validated entity statement's bundle is
 * vouched for by the anchor.)
 *
 * <p>Immutable. Load from a JSON object mapping each trust domain to a JWKS
 * ({@code {"banking.demo": {"keys": [ … ]}, …}}) via {@link #fromJson}, or build directly for tests.
 */
public final class TrustDomainBundles {

    private final Map<String, List<JsonWebKey>> byTrustDomain;

    public TrustDomainBundles(Map<String, List<JsonWebKey>> byTrustDomain) {
        Map<String, List<JsonWebKey>> copy = new LinkedHashMap<>();
        byTrustDomain.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.byTrustDomain = Map.copyOf(copy);
    }

    /**
     * Parses a JSON object mapping each trust domain to a JWKS, e.g.
     * {@code {"banking.demo": {"keys": [ {"kty":"EC", …} ]}}}.
     */
    public static TrustDomainBundles fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new TrustDomainBundles(Map.of());
        }
        Map<String, List<JsonWebKey>> byDomain = new LinkedHashMap<>();
        try {
            Map<String, Object> root = JsonUtil.parseJson(json);
            for (Map.Entry<String, Object> e : root.entrySet()) {
                List<JsonWebKey> keys = new JsonWebKeySet(JsonUtil.toJson(asMap(e.getValue()))).getJsonWebKeys();
                byDomain.put(e.getKey(), keys);
            }
        } catch (JoseException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid trust-domain bundles JSON", e);
        }
        return new TrustDomainBundles(byDomain);
    }

    /**
     * Resolves the SPIFFE bundle keys covering every supplied trust domain (their union).
     *
     * @throws IssuanceException {@code invalid_client} if any trust domain is not one the attester serves
     */
    public List<JsonWebKey> forDomains(Set<String> trustDomains) throws IssuanceException {
        List<JsonWebKey> union = new ArrayList<>();
        for (String td : trustDomains) {
            List<JsonWebKey> keys = this.byTrustDomain.get(td);
            if (keys == null || keys.isEmpty()) {
                throw IssuanceException.invalidClient("attester does not serve SPIFFE trust domain: " + td);
            }
            union.addAll(keys);
        }
        return union;
    }

    public boolean isEmpty() {
        return this.byTrustDomain.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("each trust-domain value must be a JWKS object");
        }
        return (Map<String, Object>) value;
    }
}
