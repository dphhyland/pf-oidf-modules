package com.pingidentity.ps.oidf.servlet.attestation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingidentity.ps.oidf.common.AttesterSigningKey;
import com.pingidentity.ps.oidf.common.CimdIssuanceClientResolver;
import com.pingidentity.ps.oidf.common.InstanceAttestationValidator;
import com.pingidentity.ps.oidf.common.JwsSigner;
import com.pingidentity.ps.oidf.common.LocalJwkSigner;
import com.pingidentity.ps.oidf.common.WalletInstanceAttestationValidator;
import java.util.Map;
import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.json.JsonUtil;
import org.jose4j.keys.EllipticCurves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServletEnvWiringTest {
    private static final String[] PROPS = {
            "oidf.cimd.trust.bundles", "oidf.attester.issuer.jwks", "oidf.attester.issuer.keys",
            "oidf.trust.controller.host", "oidf.attester.op.issuer", "oidf.wallet.provider.jwks"};

    @AfterEach
    void clearProps() {
        for (String p : PROPS) {
            System.clearProperty(p);
        }
    }

    @Test
    void envReadsSystemPropertyThenNull() {
        System.setProperty("oidf.attester.issuer.keys", "value");
        assertEquals("value", AttestationIssuanceServlet.env("oidf.attester.issuer.keys", "NO_SUCH_ENV"));
        assertNull(AttestationIssuanceServlet.env("oidf.absent.prop", "NO_SUCH_ENV_XYZ"));
    }

    @Test
    void parseStringMapParsesAndTolerates() {
        assertEquals(Map.of("a", "b"), AttestationIssuanceServlet.parseStringMap("{\"a\":\"b\"}"));
        assertTrue(AttestationIssuanceServlet.parseStringMap(null).isEmpty());
        assertTrue(AttestationIssuanceServlet.parseStringMap("{ not json").isEmpty());
    }

    @Test
    void parseObjectMapParsesAndSkipsNonObjects() {
        Map<String, Map<String, Object>> m =
                AttestationIssuanceServlet.parseObjectMap("{\"iss\":{\"kty\":\"EC\"},\"bad\":\"x\"}");
        assertEquals("EC", m.get("iss").get("kty"));
        assertFalse(m.containsKey("bad"));
        assertTrue(AttestationIssuanceServlet.parseObjectMap("{oops").isEmpty());
        assertTrue(AttestationIssuanceServlet.parseObjectMap(null).isEmpty());
    }

    @Test
    void cimdResolverNullUntilBundlesConfigured() throws Exception {
        assertNull(AttestationIssuanceServlet.cimdResolverFromEnv());
        PublicJsonWebKey pub = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        System.setProperty("oidf.cimd.trust.bundles",
                "{\"banking.demo\":" + new JsonWebKeySet(pub).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY) + "}");
        assertTrue(AttestationIssuanceServlet.cimdResolverFromEnv() instanceof CimdIssuanceClientResolver);
    }

    @Test
    void walletValidatorNullUntilFederationOrStaticConfigured() throws Exception {
        assertNull(AttestationIssuanceServlet.walletValidatorFromEnv());

        // static provider→JWKS map enables the wallet validator
        PublicJsonWebKey wp = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        System.setProperty("oidf.wallet.provider.jwks",
                "{\"https://wallet.example.com\":"
                        + new JsonWebKeySet(wp).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY) + "}");
        InstanceAttestationValidator v = AttestationIssuanceServlet.walletValidatorFromEnv();
        assertTrue(v instanceof WalletInstanceAttestationValidator);
        assertEquals("wallet", v.format());
    }

    @Test
    void federationWalletValidatorNeedsBothHostAndOpIssuer() throws Exception {
        System.setProperty("oidf.trust.controller.host", "https://trust-controller.example.com");
        assertNull(AttestationIssuanceServlet.federationWalletValidatorFromEnv());   // op issuer missing
        System.setProperty("oidf.attester.op.issuer", "https://attester.example.com");
        InstanceAttestationValidator v = AttestationIssuanceServlet.federationWalletValidatorFromEnv();
        assertTrue(v instanceof WalletInstanceAttestationValidator);
        assertEquals("wallet", v.format());
    }

    @Test
    void federationWalletTrustIsPreferredOverStaticMap() throws Exception {
        // The static map is unparseable, so staticWalletValidatorFromEnv() would return null; if
        // walletValidatorFromEnv() is still non-null, it must have taken the federation path.
        System.setProperty("oidf.wallet.provider.jwks", "{ not json");
        assertNull(AttestationIssuanceServlet.staticWalletValidatorFromEnv());
        System.setProperty("oidf.trust.controller.host", "https://trust-controller.example.com");
        System.setProperty("oidf.attester.op.issuer", "https://attester.example.com");
        InstanceAttestationValidator v = AttestationIssuanceServlet.walletValidatorFromEnv();
        assertTrue(v instanceof WalletInstanceAttestationValidator);
        assertEquals("wallet", v.format());
    }

    @Test
    void configureIssuerKeysAppliesEnvJwks() throws Exception {
        PublicJsonWebKey attester = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        String jwks = "{\"https://attester.example.com\":"
                + JsonUtil.toJson(attester.toParams(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE)) + "}";
        System.setProperty("oidf.attester.issuer.jwks", jwks);
        AttesterSigningKey key = AttestationIssuanceServlet.configureIssuerKeys(new AttesterSigningKey(null, null));
        JwsSigner signer = key.signerForIssuer("https://attester.example.com");
        assertTrue(signer instanceof LocalJwkSigner);
    }
}
