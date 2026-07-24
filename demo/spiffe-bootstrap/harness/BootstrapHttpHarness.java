package com.pingidentity.ps.oidf.servlet.attestation;

/*
 * SPIFFE → Client Attestation bootstrap, demonstrated over real HTTP.
 *
 * Stands the REAL AttestationIssuanceServlet behind a JDK HttpServer and drives a workload client
 * through the exact wire flow the docker-compose demo runs — minus the SPIRE agent + PingFederate
 * container wrappers, so it can be run and verified without Docker:
 *
 *   1. /spire/jwks.json           the SPIRE trust bundle (JWT authorities) — served like spire-server does
 *   2. POST /federation/attestation   the issuance servlet: JWT-SVID + instance key + PoP -> minted attestation
 *   3. POST /as/token.oauth2      a stub AS that runs the real ClientAttestationVerifier on the minted attestation
 *
 * The workload then plays its part: mint a SPIFFE JWT-SVID (what `spire-agent api fetch jwt` returns),
 * bootstrap a Client Attestation from it at (2), and use that attestation to get a token at (3).
 *
 * This is the reference the Python workload in ../workload mirrors, and the contract PingFederate serves
 * once the servlet is deployed into oidf.war. Exit 0 = the whole bootstrap succeeded.
 */
import com.pingidentity.ps.oidf.common.AttestationIssuanceConfig;
import com.pingidentity.ps.oidf.common.AttesterKeyResolver;
import com.pingidentity.ps.oidf.common.AttesterSigningKey;
import com.pingidentity.ps.oidf.common.ClientAttestationConfig;
import com.pingidentity.ps.oidf.common.ClientAttestationResult;
import com.pingidentity.ps.oidf.common.ClientAttestationVerifier;
import com.pingidentity.ps.oidf.common.InMemoryAttestationChallengeService;
import com.pingidentity.ps.oidf.common.InMemoryAttestationReplayCache;
import com.pingidentity.ps.oidf.common.InstanceKeyProofValidator;
import com.pingidentity.ps.oidf.common.StaticAttesterKeyResolver;
import com.pingidentity.ps.oidf.servlet.attestation.AttestationIssuanceServlet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.PublicJsonWebKey;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.EllipticCurves;

public final class BootstrapHttpHarness {

    static final String ATTESTER_ISSUER = "https://attester.banking.demo";
    static final String CLIENT_ID = "https://payment-agent.banking.demo";
    static final String OP_ISSUER = "https://as.banking.demo";
    static final String TOKEN_ENDPOINT = OP_ISSUER + "/as/token.oauth2";
    static final String SPIFFE_ID = "spiffe://banking.demo/payment-agent";
    static final String TRUST_DOMAIN = "banking.demo";

    static PublicJsonWebKey bundleKey;   // SPIRE trust-domain JWT authority — signs SVIDs
    static PublicJsonWebKey attesterKey; // the client's attester key — signs minted attestations (inline JWK)
    static PublicJsonWebKey instanceKey; // the workload's own key — cnf in the attestation, signs the PoP
    static AttestationIssuanceConfig issuanceConfig;
    static ClientAttestationVerifier verifier;

    public static void main(String[] args) throws Exception {
        bundleKey = ec("spire-svid-key-1");
        attesterKey = ec("mock-attester-1");
        instanceKey = ec("instance-1");
        issuanceConfig = buildIssuanceConfig();
        verifier = buildVerifier();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AttestationIssuanceServlet servlet = new AttestationIssuanceServlet();
        servlet.setClientResolver(clientId -> issuanceConfig);          // demo resolver = static config
        servlet.setAttesterSigningKey(new AttesterSigningKey(null, null)); // inline-JWK signing (no vault)

        server.createContext("/spire/jwks.json", ex -> respondJson(ex, 200, bundleJwks()));
        server.createContext("/federation/attestation", ex -> handleIssuance(ex, servlet));
        server.createContext("/as/token.oauth2", BootstrapHttpHarness::handleToken);
        // Dev convenience so an out-of-process workload (../workload/bootstrap.py) can obtain an SVID this
        // harness's bundle trusts — stands in for `spire-agent api fetch jwt` when there's no SPIRE agent.
        server.createContext("/dev/svid", ex -> respondJson(ex, 200,
                JsonUtil.toJson(Map.of("svid", svidOrError()))));

        boolean serveOnly = args.length > 0 && args[0].equals("serve");
        int fixedPort = serveOnly && args.length > 1 ? Integer.parseInt(args[1]) : 0;
        if (fixedPort != 0) {
            server.stop(0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", fixedPort), 0);
            server.createContext("/spire/jwks.json", ex -> respondJson(ex, 200, bundleJwks()));
            server.createContext("/federation/attestation", ex -> handleIssuance(ex, servlet));
            server.createContext("/as/token.oauth2", BootstrapHttpHarness::handleToken);
            server.createContext("/dev/svid", ex -> respondJson(ex, 200, JsonUtil.toJson(Map.of("svid", svidOrError()))));
        }
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        System.out.println("== SPIFFE → Client Attestation bootstrap (HTTP) ==");
        System.out.println("issuer/AS/servlet listening on " + base);

        if (serveOnly) {
            System.out.println("serve mode — endpoints: /dev/svid  /federation/attestation  /as/token.oauth2");
            System.out.println("point the workload at " + base + " (Ctrl-C to stop)");
            Thread.currentThread().join();   // block forever
            return;
        }
        try {
            System.out.println();
            runWorkload(base);
            System.out.println("\nBOOTSTRAP OK — the workload turned a SPIFFE SVID into a real token.");
        } finally {
            server.stop(0);
        }
    }

    static String svidOrError() {
        try {
            return svid(bundleKey, SPIFFE_ID, ATTESTER_ISSUER, 600L);
        } catch (Exception e) {
            return "error:" + e;
        }
    }

    // ── the workload: SVID → attestation → token ──────────────────────────────────────────────────
    static void runWorkload(String base) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // 1. Fetch the JWT-SVID. In compose this is `spire-agent api fetch jwt -audience <attester>`;
        //    here we mint one signed by the trust-domain bundle key (aud = the attester issuer).
        String svid = svid(bundleKey, SPIFFE_ID, ATTESTER_ISSUER, 600L);
        System.out.println("① JWT-SVID from the Workload API:");
        System.out.println("   sub=" + SPIFFE_ID + "  aud=" + ATTESTER_ISSUER + "  (signed by the SPIRE bundle)");

        // 2. Bootstrap: present SVID + instance public key + a PoP over the attester issuer to /federation/attestation.
        String issuanceProof = proof(instanceKey, ATTESTER_ISSUER, UUID.randomUUID().toString(), null);
        Map<String, Object> issuanceBody = Map.of(
                "client_id", CLIENT_ID,
                "instance_key", publicParams(instanceKey),
                "svid", svid,
                "proof", issuanceProof,
                "authorization_details",
                List.of(Map.of("type", "sales_agent", "sales_regions", List.of("EMEA"))));
        HttpResponse<String> issued = http.send(
                post(base + "/federation/attestation", JsonUtil.toJson(issuanceBody), Map.of()),
                HttpResponse.BodyHandlers.ofString());
        require(issued.statusCode() == 200, "issuance HTTP " + issued.statusCode() + ": " + issued.body());
        Map<String, Object> issuedJson = JsonUtil.parseJson(issued.body());
        String attestation = (String) issuedJson.get("attestation");
        require(attestation != null, "no attestation in issuance response");
        System.out.println("\n② POST /federation/attestation → 200. Minted Client Attestation:");
        printJwt("   ", attestation);
        System.out.println("   expires_in=" + issuedJson.get("expires_in"));

        // 3. Use the minted attestation to authenticate at the token endpoint (attestation + fresh PoP).
        String tokenPop = tokenPop(instanceKey, CLIENT_ID, OP_ISSUER);
        HttpResponse<String> token = http.send(
                post(base + "/as/token.oauth2", "grant_type=client_credentials",
                        Map.of("OAuth-Client-Attestation", attestation, "OAuth-Client-Attestation-PoP", tokenPop)),
                HttpResponse.BodyHandlers.ofString());
        require(token.statusCode() == 200, "token HTTP " + token.statusCode() + ": " + token.body());
        Map<String, Object> tokenJson = JsonUtil.parseJson(token.body());
        System.out.println("\n③ POST /as/token.oauth2 (attestation + PoP) → 200:");
        System.out.println("   access_token=" + preview((String) tokenJson.get("access_token"))
                + "  token_type=" + tokenJson.get("token_type"));
        System.out.println("   verified: client_id=" + tokenJson.get("client_id")
                + "  attester=" + tokenJson.get("attester") + "  mode=" + tokenJson.get("mode"));
    }

    // ── HTTP handlers standing in for spire-server + the issuance servlet + the AS ─────────────────
    static void handleIssuance(HttpExchange ex, AttestationIssuanceServlet servlet) throws java.io.IOException {
        try {
            Map<String, Object> body = JsonUtil.parseJson(readBody(ex));
            AttestationIssuanceServlet.IssuanceRequest req = new AttestationIssuanceServlet.IssuanceRequest();
            req.clientId = (String) body.get("client_id");
            req.instanceKey = asMap(body.get("instance_key"));
            req.svid = (String) body.get("svid");
            req.proof = (String) body.get("proof");
            req.requestedDetails = asDetails(body.get("authorization_details"));
            Map<String, Object> result = servlet.issue(req);
            respondJson(ex, 200, JsonUtil.toJson(result));
        } catch (com.pingidentity.ps.oidf.common.IssuanceException e) {
            respondJson(ex, statusFor(e.error()),
                    JsonUtil.toJson(Map.of("error", e.error(), "error_description", String.valueOf(e.getMessage()))));
        } catch (Exception e) {
            respondJson(ex, 500, JsonUtil.toJson(Map.of("error", "server_error", "error_description", String.valueOf(e))));
        }
    }

    static void handleToken(HttpExchange ex) throws java.io.IOException {
        try {
            readBody(ex); // drain
            String attestation = ex.getRequestHeaders().getFirst("OAuth-Client-Attestation");
            String pop = ex.getRequestHeaders().getFirst("OAuth-Client-Attestation-PoP");
            require(attestation != null && pop != null, "missing attestation headers");
            ClientAttestationResult r = verifier.verify(attestation, pop, null, "POST", TOKEN_ENDPOINT, CLIENT_ID);
            String at = "at+" + UUID.randomUUID();
            respondJson(ex, 200, JsonUtil.toJson(new java.util.LinkedHashMap<>(Map.of(
                    "access_token", at, "token_type", "Bearer", "expires_in", 3600,
                    "client_id", r.clientId(), "attester", r.attesterIssuer(),
                    "mode", String.valueOf(r.mode())))));
        } catch (Exception e) {
            respondJson(ex, 400, JsonUtil.toJson(Map.of("error", "invalid_client", "error_description", String.valueOf(e))));
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────
    static AttestationIssuanceConfig buildIssuanceConfig() throws Exception {
        Map<String, String> props = new java.util.HashMap<>();
        props.put(AttestationIssuanceConfig.P_ISSUER, ATTESTER_ISSUER);
        props.put(AttestationIssuanceConfig.P_TRUST_DOMAIN, TRUST_DOMAIN);
        // Bundle source: SPIRE_BUNDLE_URL (the compose issuer fetches the real spire-server JWKS so it
        // trusts SVIDs from the actual SPIRE agent), else the harness's own generated bundle (local run).
        String bundleUrl = System.getenv("SPIRE_BUNDLE_URL");
        props.put(AttestationIssuanceConfig.P_BUNDLE, bundleUrl != null ? fetch(bundleUrl) : bundleJwks());
        props.put(AttestationIssuanceConfig.P_SIGNING_JWK, JsonUtil.toJson(privateParams(attesterKey)));
        props.put(AttestationIssuanceConfig.P_INSTANCES,
                "[{\"spiffe_id\":\"" + SPIFFE_ID + "\","
                        + "\"entitlement\":[{\"type\":\"sales_agent\",\"actions\":[\"read_accounts\",\"create_opportunity\"],"
                        + "\"sales_regions\":[\"EMEA\"]}],"
                        + "\"metadata\":{\"region\":\"EMEA\",\"environment\":\"demo\"}}]");
        return AttestationIssuanceConfig.fromProperties(props);
    }

    static ClientAttestationVerifier buildVerifier() throws Exception {
        JsonWebKey attesterPub = JsonWebKey.Factory.newJwk(publicParams(attesterKey));
        AttesterKeyResolver resolver = new StaticAttesterKeyResolver(Map.of(ATTESTER_ISSUER, List.of(attesterPub)));
        ClientAttestationConfig cfg = ClientAttestationConfig.builder()
                .addAcceptedAudience(OP_ISSUER)
                .expectedHtu(TOKEN_ENDPOINT)
                .build();
        return new ClientAttestationVerifier(resolver, cfg,
                new InMemoryAttestationReplayCache(), new InMemoryAttestationChallengeService());
    }

    static String bundleJwks() {
        try {
            return new JsonWebKeySet(JsonWebKey.Factory.newJwk(publicParams(bundleKey))).toJson();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String fetch(String url) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(r.statusCode() == 200, "GET " + url + " -> " + r.statusCode());
        return r.body();
    }

    // ── jose + http plumbing ────────────────────────────────────────────────────────────────────
    static PublicJsonWebKey ec(String kid) throws Exception {
        PublicJsonWebKey jwk = EcJwkGenerator.generateJwk(EllipticCurves.P256);
        jwk.setKeyId(kid);
        return jwk;
    }

    static Map<String, Object> publicParams(JsonWebKey jwk) {
        return jwk.toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
    }

    static Map<String, Object> privateParams(JsonWebKey jwk) {
        return jwk.toParams(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE);
    }

    static String svid(PublicJsonWebKey key, String sub, String audience, long expOffset) throws Exception {
        JwtClaims c = new JwtClaims();
        c.setSubject(sub);
        c.setAudience(audience);
        c.setIssuedAtToNow();
        c.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() + expOffset));
        return sign(key, "ES256", "JWT", c);
    }

    static String proof(PublicJsonWebKey key, String audience, String jti, String challenge) throws Exception {
        JwtClaims c = new JwtClaims();
        c.setAudience(audience);
        c.setJwtId(jti);
        c.setIssuedAtToNow();
        if (challenge != null) {
            c.setClaim("challenge", challenge);
        }
        return sign(key, "ES256", InstanceKeyProofValidator.TYP, c);
    }

    static String tokenPop(PublicJsonWebKey key, String iss, String aud) throws Exception {
        JwtClaims c = new JwtClaims();
        c.setIssuer(iss);
        c.setAudience(aud);
        c.setJwtId("pop-" + UUID.randomUUID());
        c.setIssuedAtToNow();
        return sign(key, "ES256", "oauth-client-attestation-pop+jwt", c);
    }

    static String sign(PublicJsonWebKey key, String alg, String typ, JwtClaims claims) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(key.getPrivateKey());
        jws.setAlgorithmHeaderValue(alg);
        jws.setHeader("typ", typ);
        if (key.getKeyId() != null) {
            jws.setKeyIdHeaderValue(key.getKeyId());
        }
        return jws.getCompactSerialization();
    }

    static HttpRequest post(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", body.startsWith("{") ? "application/json" : "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return b.build();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asDetails(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    static int statusFor(String error) {
        if ("invalid_client".equals(error)) {
            return 401;
        }
        if ("server_error".equals(error)) {
            return 500;
        }
        return 400;
    }

    static String readBody(HttpExchange ex) throws java.io.IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void respondJson(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void printJwt(String indent, String jwt) {
        try {
            String[] p = jwt.split("\\.");
            System.out.println(indent + "header : " + new String(java.util.Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8));
            String payload = new String(java.util.Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
            System.out.println(indent + "payload: " + (payload.length() > 300 ? payload.substring(0, 300) + "…" : payload));
        } catch (Exception e) {
            System.out.println(indent + preview(jwt));
        }
    }

    static String preview(String s) {
        return s == null ? "null" : (s.length() > 40 ? s.substring(0, 40) + "…(" + s.length() + ")" : s);
    }

    static void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalStateException(message);
        }
    }
}
