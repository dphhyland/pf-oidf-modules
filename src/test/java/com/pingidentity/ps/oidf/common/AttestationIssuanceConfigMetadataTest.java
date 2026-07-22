package com.pingidentity.ps.oidf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jose4j.jwk.JsonWebKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttestationIssuanceConfigMetadataTest {
    private static final String ISSUER = "https://attester.example.com";
    private static final String ID = "spiffe://banking.demo/payment-agent";

    private List<JsonWebKey> bundle;

    @BeforeEach
    void setUp() throws Exception {
        bundle = List.of(JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("td-1"))));
    }

    private Map<String, Object> att() {
        return new HashMap<>(Map.of(
                "attester", ISSUER,
                "issued_ttl", 600,
                "entitlement", List.of(Map.of("type", "sales_agent", "sales_regions", List.of("EMEA", "APAC"))),
                "instances", List.of(Map.of(
                        "spiffe_id", ID,
                        "entitlement", List.of(Map.of("type", "sales_agent", "sales_regions", List.of("EMEA"))),
                        "metadata", Map.of("region", "EMEA")))));
    }

    @Test
    void buildsFromNativeMetadata() throws Exception {
        AttestationIssuanceConfig c = AttestationIssuanceConfig.fromEntityMetadata(att(), bundle);
        assertEquals(ISSUER, c.issuer());
        assertEquals(600L, c.ttlSeconds());
        assertEquals(bundle, c.bundleKeys());
        SpiffeBinding b = c.bindingFor(ID).orElseThrow();
        assertEquals("EMEA", b.metadata().get("region"));
        assertEquals(b.entitlement(), c.effectiveCeiling(b));
        // metadata configs carry no inline signing key
        assertEquals(null, c.signingKeyRef());
        assertEquals(null, c.signingJwk());
    }

    @Test
    void missingAttesterIsRejected() {
        Map<String, Object> att = att();
        att.remove("attester");
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att, bundle)).error());
    }

    @Test
    void emptyBundleIsRejected() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att(), List.of())).error());
    }

    @Test
    void instanceEntitlementExceedingCeilingIsRejected() {
        Map<String, Object> att = att();
        att.put("instances", List.of(Map.of("spiffe_id", ID,
                "entitlement", List.of(Map.of("type", "sales_agent", "sales_regions", List.of("LATAM"))))));
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att, bundle)).error());
    }

    @Test
    void trustDomainBundlesUnionAndUnknown() throws Exception {
        JsonWebKey a = JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("a")));
        JsonWebKey b = JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("b")));
        TrustDomainBundles bundles = new TrustDomainBundles(Map.of("d1", List.of(a), "d2", List.of(b)));
        assertEquals(2, bundles.forDomains(new java.util.HashSet<>(List.of("d1", "d2"))).size());
        assertEquals(1, bundles.forDomains(java.util.Set.of("d1")).size());
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> bundles.forDomains(java.util.Set.of("nope"))).error());
    }

    @Test
    void ttlNotANumberIsRejected() {
        Map<String, Object> att = att();
        att.put("issued_ttl", "abc");
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att, bundle)).error());
    }

    @Test
    void nonPositiveTtlIsRejected() {
        Map<String, Object> att = att();
        att.put("issued_ttl", -5);
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att, bundle)).error());
    }

    @Test
    void instancesNotAListIsRejected() {
        Map<String, Object> att = att();
        att.put("instances", "nope");
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(att, bundle)).error());
    }

    @Test
    void emptyMetadataIsRejected() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> AttestationIssuanceConfig.fromEntityMetadata(Map.of(), bundle)).error());
    }

    @Test
    void trustDomainBundlesMalformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> TrustDomainBundles.fromJson("{ not json"));
        assertThrows(IllegalArgumentException.class, () -> TrustDomainBundles.fromJson("{\"d\":\"not-a-jwks\"}"));
    }

    @Test
    void trustDomainBundlesFromJson() {
        String json = "{\"banking.demo\":{\"keys\":[{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"k\","
                + "\"x\":\"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU\",\"y\":\"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0\"}]}}";
        assertTrue(!TrustDomainBundles.fromJson(json).isEmpty());
        assertTrue(TrustDomainBundles.fromJson(null).isEmpty());
    }
}
