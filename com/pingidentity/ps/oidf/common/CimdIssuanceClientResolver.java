/*
 * CIMD-backed issuance config resolver (draft-ietf-oauth-client-id-metadata-document).
 */
package com.pingidentity.ps.oidf.common;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

/**
 * Sources the issuance config from a <a
 * href="https://datatracker.ietf.org/doc/draft-ietf-oauth-client-id-metadata-document/">Client ID Metadata
 * Document</a>: the {@code client_id} <em>is</em> an HTTPS URL, and the attester {@code GET}s it for an
 * unsigned JSON client-metadata document carrying an {@code oauth_client_attestation} block. Same
 * {@link AttestationIssuanceConfig#fromEntityMetadata} contract as the federation resolver — only the
 * acquisition and trust differ.
 *
 * <p><b>The trust model is the whole point.</b> A CIMD document is <em>unsigned</em> (trust = TLS + control
 * of the URL), so its SPIFFE bundle must never be self-asserted: the bundle comes from the attester's
 * configured {@link TrustDomainBundles} for the trust domains the document's {@code instances} declare, and
 * a document may only assert which {@code spiffe_id}s (under a served trust domain) are its instances. The
 * SVID (trust-domain-signed) then proves the id against the attester's bundle — which a URL-controller
 * cannot forge.
 *
 * <p>Guards per the draft §Security Considerations: {@code https}-only, no private/loopback host (SSRF), a
 * 5 KB body cap, and the document's {@code client_id} must equal the URL.
 */
public final class CimdIssuanceClientResolver implements IssuanceClientResolver {

    private static final int MAX_DOCUMENT_BYTES = 5 * 1024;

    private final HttpGetClient http;
    private final TrustDomainBundles attesterBundles;

    public CimdIssuanceClientResolver(HttpGetClient http, TrustDomainBundles attesterBundles) {
        this.http = http;
        this.attesterBundles = attesterBundles;
    }

    @Override
    public AttestationIssuanceConfig resolve(String clientId) throws IssuanceException {
        validateClientIdUrl(clientId);

        String body;
        try {
            body = this.http.get(clientId, "application/json");
        } catch (Exception e) {
            throw IssuanceException.invalidClient("could not fetch CIMD document: " + e.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw IssuanceException.invalidClient("empty CIMD document");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw IssuanceException.invalidClient("CIMD document exceeds the 5 KB limit");
        }

        Map<String, Object> doc;
        try {
            doc = JsonUtil.parseJson(body);
        } catch (JoseException e) {
            throw IssuanceException.invalidClient("CIMD document is not valid JSON");
        }
        if (!clientId.equals(doc.get("client_id"))) {
            throw IssuanceException.invalidClient("CIMD document 'client_id' does not match the requested URL");
        }

        Map<String, Object> att = attestationBlock(doc);
        if (att.isEmpty()) {
            throw IssuanceException.invalidClient("CIMD document publishes no oauth_client_attestation");
        }

        // Bundle = the attester's configured bundle for the trust domains the instances declare — NOT the
        // (self-asserted) document. Each declared trust domain must be one the attester serves.
        Set<String> domains = instanceTrustDomains(att);
        List<JsonWebKey> bundle = this.attesterBundles.forDomains(domains);
        return AttestationIssuanceConfig.fromEntityMetadata(att, bundle);
    }

    /** Draft §: https scheme, has a path, no dot-segments, no fragment, no userinfo. SSRF: refuse private/loopback hosts. */
    private static void validateClientIdUrl(String clientId) throws IssuanceException {
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (Exception e) {
            throw IssuanceException.invalidClient("client_id is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw IssuanceException.invalidClient("CIMD client_id must be an https URL");
        }
        if (uri.getHost() == null) {
            throw IssuanceException.invalidClient("CIMD client_id has no host");
        }
        if (uri.getUserInfo() != null) {
            throw IssuanceException.invalidClient("CIMD client_id must not contain userinfo");
        }
        if (uri.getFragment() != null) {
            throw IssuanceException.invalidClient("CIMD client_id must not contain a fragment");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            throw IssuanceException.invalidClient("CIMD client_id must contain a path component");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw IssuanceException.invalidClient("CIMD client_id must not contain dot-segments");
            }
        }
        // SSRF guard: refuse hosts that resolve to a loopback/link-local/site-local/any-local address.
        try {
            for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                    throw IssuanceException.invalidClient("CIMD client_id host resolves to a non-routable address");
                }
            }
        } catch (IssuanceException e) {
            throw e;
        } catch (Exception e) {
            throw IssuanceException.invalidClient("CIMD client_id host does not resolve");
        }
    }

    /** The {@code oauth_client_attestation} block — top-level (flat CIMD document) or under {@code metadata}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attestationBlock(Map<String, Object> doc) {
        Object top = doc.get("oauth_client_attestation");
        if (top instanceof Map) {
            return (Map<String, Object>) top;
        }
        Object metadata = doc.get("metadata");
        if (metadata instanceof Map) {
            Object nested = ((Map<String, Object>) metadata).get("oauth_client_attestation");
            if (nested instanceof Map) {
                return (Map<String, Object>) nested;
            }
        }
        return Map.of();
    }

    private static Set<String> instanceTrustDomains(Map<String, Object> att) throws IssuanceException {
        Set<String> domains = new HashSet<>();
        Object instances = att.get("instances");
        if (instances instanceof List) {
            for (Object item : (List<?>) instances) {
                if (item instanceof Map) {
                    Object id = ((Map<?, ?>) item).get("spiffe_id");
                    if (id != null) {
                        domains.add(SpiffeSvid.parseId(String.valueOf(id).trim())[0]);
                    }
                }
            }
        }
        return domains;
    }
}
