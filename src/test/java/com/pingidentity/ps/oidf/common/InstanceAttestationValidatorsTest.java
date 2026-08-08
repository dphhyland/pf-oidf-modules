package com.pingidentity.ps.oidf.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InstanceAttestationValidatorsTest {

    private PublicJsonWebKey signer;
    private InstanceAttestationValidators both;

    /** A no-op wallet validator — selection only routes, so the impl body is irrelevant here. */
    private static final InstanceAttestationValidator WALLET_STUB = new InstanceAttestationValidator() {
        @Override
        public String format() {
            return WalletInstanceAttestationValidator.FORMAT;
        }

        @Override
        public InstanceIdentity validate(String presented, AttestationIssuanceConfig config) {
            return null;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        signer = TestJwts.ec("k");
        both = new InstanceAttestationValidators(List.of(new SpiffeInstanceAttestationValidator(), WALLET_STUB));
    }

    private String svid(String sub) throws Exception {
        JwtClaims c = new JwtClaims();
        c.setSubject(sub);
        c.setAudience("https://attester.example.com");
        c.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        return TestJwts.sign(signer, "ES256", "JWT", c);
    }

    private String walletJwt(String typ, boolean withCnf) throws Exception {
        JwtClaims c = new JwtClaims();
        c.setIssuer("https://wallet.example.com");
        c.setSubject("urn:wallet:instance:1");
        c.setAudience("https://attester.example.com");
        c.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() + 600));
        if (withCnf) {
            Map<String, Object> cnf = new LinkedHashMap<>();
            cnf.put("jwk", TestJwts.publicParams(signer));
            c.setClaim("cnf", cnf);
        }
        return TestJwts.sign(signer, "ES256", typ, c);
    }

    @Test
    void sniffsSpiffeFromSpiffeSubject() throws Exception {
        assertEquals("spiffe", InstanceAttestationValidators.sniff(svid("spiffe://banking.demo/agent")));
    }

    @Test
    void sniffsWalletFromWiaTyp() throws Exception {
        assertEquals("wallet",
                InstanceAttestationValidators.sniff(walletJwt("wallet-instance-attestation+jwt", true)));
    }

    @Test
    void sniffsWalletFromCnfWhenTypAbsent() throws Exception {
        assertEquals("wallet", InstanceAttestationValidators.sniff(walletJwt(null, true)));
    }

    @Test
    void defaultsToSpiffeForGarbageOrPlainJwt() throws Exception {
        assertEquals("spiffe", InstanceAttestationValidators.sniff("not-a-jwt"));
        assertEquals("spiffe", InstanceAttestationValidators.sniff(null));
        assertEquals("spiffe", InstanceAttestationValidators.sniff(walletJwt("JWT", false)));
    }

    @Test
    void explicitFormatWinsOverSniff() throws Exception {
        // A SPIFFE-looking token, but the request declares wallet → wallet validator selected.
        InstanceAttestationValidator v = both.select("wallet", svid("spiffe://d/x"));
        assertEquals("wallet", v.format());
    }

    @Test
    void selectSniffsWhenFormatUndeclared() throws Exception {
        assertEquals("spiffe", both.select(null, svid("spiffe://d/x")).format());
        assertEquals("wallet", both.select("  ", walletJwt("wallet-attestation+jwt", true)).format());
    }

    @Test
    void unsupportedFormatIsRejected() throws Exception {
        assertEquals("invalid_request",
                assertThrows(IssuanceException.class, () -> both.select("device", svid("spiffe://d/x"))).error());
    }

    @Test
    void spiffeOnlyRegistryRejectsWallet() throws Exception {
        InstanceAttestationValidators reg = InstanceAttestationValidators.spiffeOnly();
        assertTrue(reg.supports("spiffe"));
        assertEquals("invalid_request", assertThrows(IssuanceException.class,
                () -> reg.select("wallet", walletJwt("wallet-instance-attestation+jwt", true))).error());
    }
}
