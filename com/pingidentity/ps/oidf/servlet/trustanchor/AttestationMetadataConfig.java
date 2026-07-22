/*
 * OP metadata describing attestation-based client authentication support (advertised in the
 * Entity Configuration's openid_provider metadata).
 */
package com.pingidentity.ps.oidf.servlet.trustanchor;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletConfig;

/**
 * Capability lists advertised under {@code metadata.openid_provider} for attestation-based client
 * authentication (draft-ietf-oauth-attestation-based-client-auth-10): the supported token endpoint
 * auth methods, the accepted proof-of-possession methods ({@code client_attestation_pop_methods_supported},
 * values from the "OAuth Client Attestation Proof-of-Possession Methods" registry) and the
 * attestation / PoP / DPoP signing algorithm sets, plus whether the challenge endpoint is advertised.
 * All values default sensibly and may be overridden via servlet init-params.
 *
 * <p>Per draft -10 the DPoP combined mode is no longer a distinct auth method: clients use
 * {@code attest_jwt_client_auth} and the server advertises {@code dpop_combined} as an accepted
 * PoP method instead of the pre-10 {@code attest_jwt_client_auth_dpop} method name.
 */
final class AttestationMetadataConfig {
    private static final List<String> DEFAULT_AUTH_METHODS = List.of("private_key_jwt", "attest_jwt_client_auth");
    private static final List<String> DEFAULT_POP_METHODS = List.of("attestation_pop_jwt", "dpop_combined");
    private static final List<String> DEFAULT_ATTESTATION_ALGS = List.of("RS256", "PS256", "ES256");
    private static final List<String> DEFAULT_POP_ALGS = List.of("ES256", "RS256", "PS256");
    private static final List<String> DEFAULT_DPOP_ALGS = List.of("ES256", "RS256", "PS256");
    private static final List<String> DEFAULT_FORMATS = List.of("jwt");

    private final List<String> tokenEndpointAuthMethodsSupported;
    private final List<String> clientAttestationPopMethodsSupported;
    private final List<String> clientAttestationSigningAlgValuesSupported;
    private final List<String> clientAttestationPopSigningAlgValuesSupported;
    private final List<String> dpopSigningAlgValuesSupported;
    private final List<String> clientAttestationFormatsSupported;
    private final boolean challengeEndpointEnabled;

    AttestationMetadataConfig(List<String> tokenEndpointAuthMethodsSupported,
                             List<String> clientAttestationPopMethodsSupported,
                             List<String> clientAttestationSigningAlgValuesSupported,
                             List<String> clientAttestationPopSigningAlgValuesSupported,
                             List<String> dpopSigningAlgValuesSupported,
                             List<String> clientAttestationFormatsSupported,
                             boolean challengeEndpointEnabled) {
        this.tokenEndpointAuthMethodsSupported = List.copyOf(tokenEndpointAuthMethodsSupported);
        this.clientAttestationPopMethodsSupported = List.copyOf(clientAttestationPopMethodsSupported);
        this.clientAttestationSigningAlgValuesSupported = List.copyOf(clientAttestationSigningAlgValuesSupported);
        this.clientAttestationPopSigningAlgValuesSupported = List.copyOf(clientAttestationPopSigningAlgValuesSupported);
        this.dpopSigningAlgValuesSupported = List.copyOf(dpopSigningAlgValuesSupported);
        this.clientAttestationFormatsSupported = List.copyOf(clientAttestationFormatsSupported);
        this.challengeEndpointEnabled = challengeEndpointEnabled;
    }

    static AttestationMetadataConfig defaults() {
        return new AttestationMetadataConfig(DEFAULT_AUTH_METHODS, DEFAULT_POP_METHODS, DEFAULT_ATTESTATION_ALGS, DEFAULT_POP_ALGS, DEFAULT_DPOP_ALGS, DEFAULT_FORMATS, true);
    }

    static AttestationMetadataConfig fromServletConfig(ServletConfig config) {
        List<String> authMethods = AttestationMetadataConfig.parseList(config.getInitParameter("tokenEndpointAuthMethodsSupported"), DEFAULT_AUTH_METHODS);
        List<String> popMethods = AttestationMetadataConfig.parseList(config.getInitParameter("clientAttestationPopMethodsSupported"), DEFAULT_POP_METHODS);
        List<String> attestationAlgs = AttestationMetadataConfig.parseList(config.getInitParameter("clientAttestationSigningAlgValuesSupported"), DEFAULT_ATTESTATION_ALGS);
        List<String> popAlgs = AttestationMetadataConfig.parseList(config.getInitParameter("clientAttestationPopSigningAlgValuesSupported"), DEFAULT_POP_ALGS);
        List<String> dpopAlgs = AttestationMetadataConfig.parseList(config.getInitParameter("dpopSigningAlgValuesSupported"), DEFAULT_DPOP_ALGS);
        List<String> formats = AttestationMetadataConfig.parseList(config.getInitParameter("clientAttestationFormatsSupported"), DEFAULT_FORMATS);
        boolean challengeEnabled = AttestationMetadataConfig.parseBoolean(config.getInitParameter("attestationChallengeEndpointEnabled"), true);
        return new AttestationMetadataConfig(authMethods, popMethods, attestationAlgs, popAlgs, dpopAlgs, formats, challengeEnabled);
    }

    List<String> tokenEndpointAuthMethodsSupported() {
        return this.tokenEndpointAuthMethodsSupported;
    }

    List<String> clientAttestationPopMethodsSupported() {
        return this.clientAttestationPopMethodsSupported;
    }

    List<String> clientAttestationSigningAlgValuesSupported() {
        return this.clientAttestationSigningAlgValuesSupported;
    }

    List<String> clientAttestationPopSigningAlgValuesSupported() {
        return this.clientAttestationPopSigningAlgValuesSupported;
    }

    List<String> dpopSigningAlgValuesSupported() {
        return this.dpopSigningAlgValuesSupported;
    }

    List<String> clientAttestationFormatsSupported() {
        return this.clientAttestationFormatsSupported;
    }

    boolean challengeEndpointEnabled() {
        return this.challengeEndpointEnabled;
    }

    private static List<String> parseList(String value, List<String> fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        ArrayList<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? fallback : List.copyOf(result);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
