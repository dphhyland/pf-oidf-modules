/*
 * Sources the issuance config from a client entity's OpenID Federation metadata (chain-validated).
 */
package com.pingidentity.ps.oidf.common;

import java.util.List;
import java.util.Map;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwt.JwtClaims;

/**
 * Sources a client's issuance config from its <b>OpenID Federation</b> metadata instead of PingFederate
 * extended properties. Mirrors {@link FederationAttesterKeyResolver}: validate the trust chain for the
 * {@code client_id} (which is the client's federation {@code entity_id}) to the configured anchor, take the
 * verified leaf entity statement, and read an {@code oauth_client_attestation} metadata block off it.
 *
 * <p>The trust bundle is read straight from that metadata because it is <b>chain-vouched</b> — the anchor
 * vouches for the entity, so the entity's self-published SPIFFE bundle is trusted (unlike CIMD, where the
 * document is unsigned and the bundle must come from the attester's own config). Membership/status is
 * enforced by the chain validating: a suspended/revoked entity no longer resolves to the anchor (the
 * controller runs with its cache disabled), so {@link TrustChainValidator#validate} throws.
 *
 * <p>Metadata-sourced configs carry no private signing key; the attester signs by {@code issuer} via
 * {@link AttesterSigningKey#signerForIssuer}.
 */
public final class FederationIssuanceClientResolver implements IssuanceClientResolver {

    private final TrustChainValidator trustChainValidator;
    private final String opIssuer;
    private final long trustChainEntryMaxAgeSeconds;

    public FederationIssuanceClientResolver(TrustChainValidator trustChainValidator, String opIssuer) {
        this(trustChainValidator, opIssuer, -1L);
    }

    public FederationIssuanceClientResolver(TrustChainValidator trustChainValidator, String opIssuer,
                                            long trustChainEntryMaxAgeSeconds) {
        this.trustChainValidator = trustChainValidator;
        this.opIssuer = opIssuer;
        this.trustChainEntryMaxAgeSeconds = trustChainEntryMaxAgeSeconds;
    }

    @Override
    public AttestationIssuanceConfig resolve(String clientId) throws IssuanceException {
        TrustChainValidationResult result;
        try {
            // Treat the client_id as the leaf entity identifier; validate its chain to the anchor. This
            // both establishes membership/status and yields the verified leaf statement.
            result = this.trustChainValidator.validate(
                    List.of(), clientId, this.opIssuer, -1L, -1L, this.trustChainEntryMaxAgeSeconds);
        } catch (Exception e) {
            throw IssuanceException.invalidClient(
                    "client entity did not validate to the trust anchor: " + e.getMessage());
        }

        JwtClaims leaf = result.leafEntityStatement();
        Map<String, Object> metadata = Claims.optionalMap(leaf, "metadata");
        Map<String, Object> att = Claims.optionalNestedMap(metadata, "oauth_client_attestation");
        if (att.isEmpty()) {
            throw IssuanceException.invalidClient("client entity publishes no oauth_client_attestation metadata");
        }

        // Chain-vouched: read the bundle straight from the (validated) entity metadata.
        Map<String, Object> bundleMap = Claims.optionalNestedMap(att, "spiffe_trust_bundle");
        if (bundleMap.isEmpty()) {
            throw IssuanceException.invalidClient("oauth_client_attestation carries no spiffe_trust_bundle");
        }
        List<JsonWebKey> bundle;
        try {
            bundle = new JsonWebKeySet(JsonUtil.toJson(bundleMap)).getJsonWebKeys();
        } catch (Exception e) {
            throw IssuanceException.invalidClient("spiffe_trust_bundle is not a valid JWKS");
        }
        if (bundle.isEmpty()) {
            throw IssuanceException.invalidClient("spiffe_trust_bundle carries no keys");
        }

        return AttestationIssuanceConfig.fromEntityMetadata(att, bundle);
    }
}
