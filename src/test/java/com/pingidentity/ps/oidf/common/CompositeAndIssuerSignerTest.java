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
}
