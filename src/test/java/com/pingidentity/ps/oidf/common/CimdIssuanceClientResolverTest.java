package com.pingidentity.ps.oidf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.json.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CimdIssuanceClientResolverTest {
    private static final String ISSUER = "https://attester.example.com";
    private static final String SPIFFE_ID = "spiffe://banking.demo/payment-agent";
    // Public IP literals so URL/SSRF validation runs without DNS. TEST-NET-3 (203.0.113.0/24) is routable-looking.
    private static final String CID = "https://203.0.113.10/cimd/payment-agent";

    private JsonWebKey tdKey;                 // the attester's REAL trust-domain bundle key
    private TrustDomainBundles attesterBundles;
    private FakeHttp http;
    private CimdIssuanceClientResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        tdKey = JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("real-td-key")));
        attesterBundles = new TrustDomainBundles(Map.of("banking.demo", List.of(tdKey)));
        http = new FakeHttp();
        resolver = new CimdIssuanceClientResolver(http, attesterBundles);
    }

    private Map<String, Object> attBlock() {
        return new HashMap<>(Map.of(
                "attester", ISSUER,
                "issued_ttl", 300,
                "instances", List.of(Map.of(
                        "spiffe_id", SPIFFE_ID,
                        "entitlement", List.of(Map.of("type", "sales_agent", "sales_regions", List.of("EMEA"))),
                        "metadata", Map.of("region", "EMEA")))));
    }

    private void publish(String clientId, Map<String, Object> doc) {
        http.docs.put(clientId, JsonUtil.toJson(doc));
    }

    @Test
    void happyPathBuildsConfigFromDocument() throws Exception {
        publish(CID, Map.of("client_id", CID, "oauth_client_attestation", attBlock()));
        AttestationIssuanceConfig config = resolver.resolve(CID);
        assertEquals(ISSUER, config.issuer());
        assertEquals(300L, config.ttlSeconds());
        assertEquals(SPIFFE_ID, config.bindings().get(0).spiffeId());
        // metadata-sourced → no inline signing key (resolved by issuer)
        assertEquals(null, config.signingKeyRef());
    }

    @Test
    void bundleComesFromAttesterNotTheDocument() throws Exception {
        // The document self-asserts an ATTACKER bundle; it must be ignored — the attester's bundle wins.
        JsonWebKey attackerKey = JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("attacker-key")));
        Map<String, Object> att = attBlock();
        att.put("spiffe_trust_bundle", new org.jose4j.jwk.JsonWebKeySet(attackerKey).toJson());
        publish(CID, Map.of("client_id", CID, "oauth_client_attestation", att));

        AttestationIssuanceConfig config = resolver.resolve(CID);
        assertEquals("real-td-key", config.bundleKeys().get(0).getKeyId());   // NOT "attacker-key"
    }

    @Test
    void clientIdMismatchIsRejected() throws Exception {
        publish(CID, Map.of("client_id", "https://203.0.113.10/other", "oauth_client_attestation", attBlock()));
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve(CID)).error());
    }

    @Test
    void nonHttpsIsRejected() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve("http://203.0.113.10/x")).error());
    }

    @Test
    void loopbackHostIsRejectedSsrf() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve("https://127.0.0.1/cimd")).error());
    }

    @Test
    void privateHostIsRejectedSsrf() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve("https://10.0.0.5/cimd")).error());
    }

    @Test
    void missingPathIsRejected() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve("https://203.0.113.10/")).error());
    }

    @Test
    void unknownTrustDomainIsRejected() throws Exception {
        Map<String, Object> att = new HashMap<>(attBlock());
        att.put("instances", List.of(Map.of("spiffe_id", "spiffe://other.domain/x")));
        publish(CID, Map.of("client_id", CID, "oauth_client_attestation", att));
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve(CID)).error());
    }

    @Test
    void oversizeDocumentIsRejected() {
        http.docs.put(CID, "{\"pad\":\"" + "x".repeat(6000) + "\"}");
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve(CID)).error());
    }

    @Test
    void noAttestationBlockIsRejected() {
        publish(CID, Map.of("client_id", CID, "redirect_uris", List.of("https://x/cb")));
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> resolver.resolve(CID)).error());
    }

    /** In-memory HttpGetClient keyed by URL. */
    static final class FakeHttp implements HttpGetClient {
        final Map<String, String> docs = new HashMap<>();

        @Override
        public String get(String url, String acceptHeader) {
            String body = this.docs.get(url);
            if (body == null) {
                throw new RuntimeException("404 " + url);
            }
            return body;
        }
    }
}
