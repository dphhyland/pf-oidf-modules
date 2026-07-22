package com.pingidentity.ps.oidf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.jose4j.jwk.JsonWebKey;
import org.junit.jupiter.api.Test;

class CompositeAndIssuerSignerTest {

    private AttestationIssuanceConfig configWithIssuer(String issuer) throws Exception {
        JsonWebKey k = JsonWebKey.Factory.newJwk(TestJwts.publicParams(TestJwts.ec("k")));
        return AttestationIssuanceConfig.fromEntityMetadata(
                Map.of("attester", issuer, "instances", List.of(Map.of("spiffe_id", "spiffe://d/x"))), List.of(k));
    }

    @Test
    void firstSuccessWins() throws Exception {
        AttestationIssuanceConfig cfgA = configWithIssuer("iss-A");
        AttestationIssuanceConfig cfgB = configWithIssuer("iss-B");
        IssuanceClientResolver a = id -> cfgA;
        IssuanceClientResolver b = id -> cfgB;
        assertEquals("iss-A", CompositeIssuanceClientResolver.of(a, b).resolve("x").issuer());
    }

    @Test
    void fallsThroughAThrowToTheNextSuccess() throws Exception {
        AttestationIssuanceConfig cfgB = configWithIssuer("iss-B");
        IssuanceClientResolver a = id -> { throw IssuanceException.invalidClient("A not mine"); };
        IssuanceClientResolver b = id -> cfgB;
        assertEquals("iss-B", CompositeIssuanceClientResolver.of(a, b).resolve("x").issuer());
    }

    @Test
    void allFailRethrowsTheFirst() {
        IssuanceClientResolver a = id -> { throw IssuanceException.invalidSvid("first"); };
        IssuanceClientResolver b = id -> { throw IssuanceException.invalidClient("second"); };
        IssuanceException e = assertThrows(IssuanceException.class,
                () -> CompositeIssuanceClientResolver.of(a, b).resolve("x"));
        assertEquals("invalid_svid", e.error());   // the first (highest-assurance) resolver's error
    }

    @Test
    void signerForIssuerResolvesInlineByIssuer() throws Exception {
        JsonWebKey attester = TestJwts.ec("attester-1");
        AttesterSigningKey ask = new AttesterSigningKey(null, null)
                .withIssuerKeys(Map.of(), Map.of("https://attester.example.com", TestJwts.privateParams(attester)));
        JwsSigner signer = ask.signerForIssuer("https://attester.example.com");
        assertTrue(signer instanceof LocalJwkSigner);
        assertEquals("ES256", signer.algorithm());
    }

    @Test
    void signerForUnknownIssuerIsServerError() {
        AttesterSigningKey ask = new AttesterSigningKey(null, null);
        assertEquals("server_error",
                assertThrows(IssuanceException.class, () -> ask.signerForIssuer("https://nobody")).error());
    }

    @Test
    void withIssuerKeysToleratesNullMaps() {
        AttesterSigningKey ask = new AttesterSigningKey(null, null).withIssuerKeys(null, null);
        assertEquals("server_error",
                assertThrows(IssuanceException.class, () -> ask.signerForIssuer("https://x")).error());
    }

    @Test
    void fromEnvironmentReadsSystemProperties() {
        try {
            System.setProperty("oidf.openbao.url", "http://openbao.local:8200");
            System.setProperty("oidf.openbao.token", "t");
            // exercises the sysprop-first resolution chain; keyRef signing needs the vault, so it errors closed
            AttesterSigningKey ask = AttesterSigningKey.fromEnvironment()
                    .withIssuerKeys(Map.of("https://a", "some-key"), Map.of());
            assertEquals("server_error",
                    assertThrows(IssuanceException.class, () -> ask.signerForIssuer("https://a")).error());
        } finally {
            System.clearProperty("oidf.openbao.url");
            System.clearProperty("oidf.openbao.token");
        }
    }

    @Test
    void emptyCompositeIsRejected() {
        assertEquals("invalid_client",
                assertThrows(IssuanceException.class, () -> CompositeIssuanceClientResolver.of().resolve("x")).error());
    }

    @Test
    void signerForIssuerPrefersKeyRefOverInline() throws Exception {
        // issuer in both maps → the transit key ref wins; with no vault configured that surfaces as server_error
        // (proving the key-ref branch was taken, not the inline JWK).
        AttesterSigningKey ask = new AttesterSigningKey(null, null).withIssuerKeys(
                Map.of("https://a", "some-transit-key"),
                Map.of("https://a", TestJwts.privateParams(TestJwts.ec("x"))));
        assertEquals("server_error",
                assertThrows(IssuanceException.class, () -> ask.signerForIssuer("https://a")).error());
    }
}
