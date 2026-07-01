/*
 * PingFederate issuance-criteria hook for attestation-based client authentication.
 */
package com.pingidentity.ps.oidf.servlet.clientregistration.utils;

import com.pingidentity.ps.oidf.common.AttestationSupport;
import com.pingidentity.ps.oidf.common.AttesterKeyResolver;
import com.pingidentity.ps.oidf.common.ClientAttestationConfig;
import com.pingidentity.ps.oidf.common.ClientAttestationException;
import com.pingidentity.ps.oidf.common.ClientAttestationResult;
import com.pingidentity.ps.oidf.common.ClientAttestationVerifier;
import com.pingidentity.ps.oidf.common.FederationAttesterKeyResolver;
import com.pingidentity.ps.oidf.common.HttpTrustControllerGateway;
import com.pingidentity.ps.oidf.common.JdkHttpGetClient;
import com.pingidentity.ps.oidf.common.StaticAttesterKeyResolver;
import com.pingidentity.ps.oidf.common.TrustChainValidator;
import com.pingidentity.ps.oidf.common.TrustControllerGateway;
import com.pingidentity.ps.oidf.servlet.clientregistration.RegistrationConfiguration;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sourceid.oauth20.issuer.OAuthIssuerUtils;
import org.sourceid.saml20.adapter.attribute.AttributeValue;

/**
 * Runtime entry point for attestation-based client authentication, designed to be called from a
 * PingFederate token-endpoint OAuth issuance-criteria OGNL expression, e.g.
 * {@code ClientAttestationUtils.validateClientAttestation(#this)}. It mirrors
 * {@link OIDFederationUtils#validateTrustChain(Object)}: it receives the criteria context map (with
 * {@code context.HttpRequest} and {@code context.ClientId}), verifies the
 * {@code OAuth-Client-Attestation} header together with either {@code OAuth-Client-Attestation-PoP}
 * (PoP-JWT mode) or {@code DPoP} (combined mode), and returns {@code true}/{@code false}.
 *
 * <p>Attester trust is resolved via the OpenID Federation trust chain (reusing {@link TrustChainValidator}).
 * Optional per-client tuning is read from {@code extproperties.*} (see {@link #buildConfig}).
 */
public final class ClientAttestationUtils {
    private static final Log LOGGER = LogFactory.getLog(ClientAttestationUtils.class);
    private static final Object LOCK = new Object();
    private static volatile TrustControllerGateway gateway;
    private static volatile TrustChainValidator validator;
    private static volatile Boolean configuredIgnoreSslErrors;
    private static volatile String configuredTrustControllerHost;

    private ClientAttestationUtils() {
    }

    public static boolean validateClientAttestation(Object inObj) {
        return ClientAttestationUtils.validateClientAttestation(inObj, RegistrationConfiguration._IGNORE_SSL_ERRORS, RegistrationConfiguration._TRUST_CONTROLLER_HOST);
    }

    public static boolean validateClientAttestation(Object inObj, Boolean ignoreSslErrors, String trustControllerHost) {
        try {
            if (!(inObj instanceof Map)) {
                LOGGER.error((Object) ("In parameters not instance of Map. " + (inObj == null ? "null" : inObj.getClass().getName())));
                return false;
            }
            Map inParameters = (Map) inObj;
            HttpServletRequest request = (HttpServletRequest) ((AttributeValue) inParameters.get("context.HttpRequest")).getObjectValue();
            String requestedClientId = ClientAttestationUtils.attributeValue(inParameters, "context.ClientId");
            String opIssuer = OAuthIssuerUtils.getInstance().getIssuerValue(request);

            String attestation = ClientAttestationUtils.singleHeader(request, "OAuth-Client-Attestation");
            String pop = ClientAttestationUtils.singleHeader(request, "OAuth-Client-Attestation-PoP");
            String dpop = ClientAttestationUtils.singleHeader(request, "DPoP");
            String requestUri = request.getRequestURL() == null ? null : request.getRequestURL().toString();

            AttesterKeyResolver resolver = ClientAttestationUtils.mockAttesterResolver();
            if (resolver == null) {
                TrustChainValidator chainValidator = ClientAttestationUtils.getValidator(ignoreSslErrors, trustControllerHost);
                resolver = new FederationAttesterKeyResolver(chainValidator, opIssuer, ClientAttestationUtils.trustChainEntryMaxAge(inParameters));
            }
            ClientAttestationConfig config = ClientAttestationUtils.buildConfig(inParameters, opIssuer, requestUri);
            ClientAttestationVerifier verifier = new ClientAttestationVerifier(resolver, config, AttestationSupport.replayCache(), AttestationSupport.challengeService());

            ClientAttestationResult result = verifier.verify(attestation, pop, dpop, request.getMethod(), requestUri, requestedClientId);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info((Object) ("Attestation-based client authentication succeeded for client_id=" + result.clientId() + " mode=" + result.mode() + " attester=" + result.attesterIssuer()));
            }
            return true;
        } catch (ClientAttestationException e) {
            LOGGER.info((Object) ("Attestation-based client authentication failed [" + e.error() + "]: " + e.getMessage()));
            return false;
        } catch (Exception e) {
            LOGGER.info((Object) "Attestation-based client authentication failed", (Throwable) e);
            return false;
        }
    }

    private static volatile AttesterKeyResolver mockResolver;
    private static volatile boolean mockResolverLoaded;

    /**
     * DEV/DEMO hook: if the {@code oidf.mock.attesters} system property points to a readable
     * mock-attester JWKS file, returns a {@link StaticAttesterKeyResolver} that trusts those keys
     * directly (no federation trust chain). Returns {@code null} in normal operation.
     */
    private static AttesterKeyResolver mockAttesterResolver() {
        if (mockResolverLoaded) {
            return mockResolver;
        }
        synchronized (LOCK) {
            if (!mockResolverLoaded) {
                String path = System.getProperty("oidf.mock.attesters");
                if (path != null && !path.isBlank() && java.nio.file.Files.isReadable(java.nio.file.Path.of(path))) {
                    try {
                        mockResolver = StaticAttesterKeyResolver.fromFile(java.nio.file.Path.of(path));
                        LOGGER.warn((Object) ("DEV MODE: trusting static mock attester keys from '" + path
                                + "' — OpenID Federation trust-chain validation is DISABLED."));
                    } catch (Exception e) {
                        LOGGER.error((Object) ("Failed to load oidf.mock.attesters file '" + path + "'"), (Throwable) e);
                    }
                }
                mockResolverLoaded = true;
            }
        }
        return mockResolver;
    }

    private static TrustChainValidator getValidator(boolean ignoreSslErrors, String trustControllerHost) {
        TrustChainValidator local = validator;
        if (local != null) {
            ClientAttestationUtils.validateConfiguration(ignoreSslErrors, trustControllerHost);
            return local;
        }
        synchronized (LOCK) {
            if (validator == null) {
                gateway = new HttpTrustControllerGateway(new JdkHttpGetClient(ignoreSslErrors), trustControllerHost);
                configuredIgnoreSslErrors = ignoreSslErrors;
                configuredTrustControllerHost = trustControllerHost;
                validator = new TrustChainValidator(gateway, trustControllerHost);
            } else {
                ClientAttestationUtils.validateConfiguration(ignoreSslErrors, trustControllerHost);
            }
            return validator;
        }
    }

    private static void validateConfiguration(boolean ignoreSslErrors, String trustControllerHost) {
        if (!java.util.Objects.equals(configuredIgnoreSslErrors, ignoreSslErrors) || !java.util.Objects.equals(configuredTrustControllerHost, trustControllerHost)) {
            throw new IllegalStateException("TrustControllerGateway already initialized with different configuration");
        }
    }

    /**
     * Builds the verification policy, defaulting the PoP audience to the OP issuer and the request URL,
     * and reading optional {@code extproperties.*} overrides: {@code attestation_pop_max_age},
     * {@code attestation_dpop_max_age}, {@code attestation_clock_skew},
     * {@code attestation_challenge_required}, {@code attestation_expected_htu},
     * {@code attestation_accepted_algs}, {@code attestation_pop_algs}, {@code attestation_dpop_algs}.
     */
    private static ClientAttestationConfig buildConfig(Map inParameters, String opIssuer, String requestUri) {
        ClientAttestationConfig.Builder b = ClientAttestationConfig.builder()
                .addAcceptedAudience(opIssuer)
                .addAcceptedAudience(requestUri)
                .expectedHtm("POST");

        Long popMaxAge = ClientAttestationUtils.longProp(inParameters, "extproperties.attestation_pop_max_age");
        if (popMaxAge != null) {
            b.popMaxAgeSeconds(popMaxAge);
        }
        Long dpopMaxAge = ClientAttestationUtils.longProp(inParameters, "extproperties.attestation_dpop_max_age");
        if (dpopMaxAge != null) {
            b.dpopMaxAgeSeconds(dpopMaxAge);
        }
        Long clockSkew = ClientAttestationUtils.longProp(inParameters, "extproperties.attestation_clock_skew");
        if (clockSkew != null) {
            b.allowedClockSkewSeconds(clockSkew.intValue());
        }
        Boolean challengeRequired = ClientAttestationUtils.boolProp(inParameters, "extproperties.attestation_challenge_required");
        if (challengeRequired != null) {
            b.challengeRequired(challengeRequired);
        }
        String expectedHtu = ClientAttestationUtils.stringProp(inParameters, "extproperties.attestation_expected_htu");
        if (expectedHtu != null) {
            b.expectedHtu(expectedHtu);
        }
        Set<String> attAlgs = ClientAttestationUtils.setProp(inParameters, "extproperties.attestation_accepted_algs");
        if (attAlgs != null) {
            b.attestationAlgorithms(attAlgs);
        }
        Set<String> popAlgs = ClientAttestationUtils.setProp(inParameters, "extproperties.attestation_pop_algs");
        if (popAlgs != null) {
            b.popAlgorithms(popAlgs);
        }
        Set<String> dpopAlgs = ClientAttestationUtils.setProp(inParameters, "extproperties.attestation_dpop_algs");
        if (dpopAlgs != null) {
            b.dpopAlgorithms(dpopAlgs);
        }
        return b.build();
    }

    private static long trustChainEntryMaxAge(Map inParameters) {
        Long value = ClientAttestationUtils.longProp(inParameters, "extproperties.trust_chain_request_max_age");
        return value != null ? value : -1L;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) {
            return null;
        }
        String first = null;
        int count = 0;
        while (values.hasMoreElements()) {
            String v = values.nextElement();
            if (count == 0) {
                first = v;
            }
            ++count;
        }
        if (count == 0) {
            return null;
        }
        if (count > 1) {
            throw new IllegalArgumentException("Multiple '" + name + "' headers present; exactly one is required");
        }
        return first;
    }

    private static String attributeValue(Map inParameters, String key) {
        Object value = inParameters.get(key);
        if (value instanceof AttributeValue) {
            return ((AttributeValue) value).getValue();
        }
        return null;
    }

    private static String stringProp(Map inParameters, String key) {
        if (!inParameters.containsKey(key)) {
            return null;
        }
        String value = String.valueOf(inParameters.get(key));
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long longProp(Map inParameters, String key) {
        String value = ClientAttestationUtils.stringProp(inParameters, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOGGER.warn((Object) (key + " is not an integer (\"" + value + "\"); ignoring"));
            return null;
        }
    }

    private static Boolean boolProp(Map inParameters, String key) {
        String value = ClientAttestationUtils.stringProp(inParameters, key);
        return value == null ? null : Boolean.valueOf(Boolean.parseBoolean(value));
    }

    private static Set<String> setProp(Map inParameters, String key) {
        String value = ClientAttestationUtils.stringProp(inParameters, key);
        if (value == null) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : result;
    }
}
