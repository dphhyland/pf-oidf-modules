# Design — federation-backed attestation issuance

Sources the hosted attester's per-client config (SPIFFE trust bundle + instance bindings + entitlements)
from the **client entity's OpenID Federation metadata**, resolved through the trust controller, instead
of from PingFederate client extended properties. It drops into the existing
[`IssuanceClientResolver`](../com/pingidentity/ps/oidf/common/IssuanceClientResolver.java) seam — the
downstream SVID validation, binding match, proof check, and minting in
[`AttestationIssuanceServlet`](../com/pingidentity/ps/oidf/servlet/attestation/AttestationIssuanceServlet.java)
are unchanged. Companion to [attestation-issuance.md](attestation-issuance.md).

**Scope:** *Phase 1* — the workload presents its `client_id` (which **is** its federation `entity_id`), and
the attester pulls that entity's attestation config from federation. It needs **no new trust-controller
endpoint**. The `spiffe_id → entity_id` reverse lookup (SVID-only requests) is out of scope — see
"Reverse resolution" at the end.

**Three sources, one contract.** The metadata block below (`oauth_client_attestation`) and the
`AttestationIssuanceConfig.fromEntityMetadata(...)` factory are shared by **all** config sources — PF
extended properties, OpenID Federation, and **CIMD** ([Client ID Metadata Document](https://datatracker.ietf.org/doc/draft-ietf-oauth-client-id-metadata-document/),
covered near the end). They differ only in *how the metadata is acquired and what vouches for it*.

## Why this is a small change

The federation machinery already exists and one class does almost exactly this shape:
[`FederationAttesterKeyResolver`](../com/pingidentity/ps/oidf/common/FederationAttesterKeyResolver.java)
"validate a chain for an `iss` → pull a keyset out of a **custom** `metadata.oauth_client_attester.jwks`
block." This resolver is the same move, reading a `spiffe_trust_bundle` + `instances` block instead. And
`entity_id == client_id` already (an RP's entity identifier becomes its PF client_id at registration), so
the request needs nothing new.

## The metadata contract

A client entity publishes a new metadata type in its self-signed **entity configuration** (served at its
`entity_id`, returned in `/resolve`'s `metadata` block). Named `oauth_client_attestation` — symmetric with
the existing attester-side `oauth_client_attester`:

```jsonc
"metadata": {
  "oauth_client_attestation": {
    "attester": "https://attester.example.com",          // home attester entity_id (= minted iss & required SVID aud)
    "issued_ttl": 300,
    "spiffe_trust_bundle": { "keys": [ /* trust-domain JWT authorities */ ] },
    "instances": [
      { "spiffe_id": "spiffe://banking.demo/payment-agent",
        "entitlement": [ { "type": "sales_agent", "sales_regions": ["EMEA"] } ],
        "metadata": { "region": "EMEA", "environment": "prod" } }
    ]
  }
}
```

These fields are a 1:1 image of today's `attestation_*` extended properties — so the change is *where the
same config comes from*, not new semantics. (`spiffe_trust_bundle` may later be replaced by a reference to
a **trust-domain federation entity** that publishes the bundle; inline JWKS is Phase 1.)

## The resolver

`FederationIssuanceClientResolver implements IssuanceClientResolver` — mirrors
`FederationAttesterKeyResolver`:

```java
public AttestationIssuanceConfig resolve(String clientId) throws IssuanceException {
    // clientId == the workload's federation entity_id
    // 1. membership + status gate (the seam PfIssuanceClientResolver did with Client.isEnabled())
    JwtClaims resolveResponse = gateway.resolveEntity(clientId);            // HttpTrustControllerGateway
    // resolveEntity throws / non-verifying == not an active member → invalid_client

    // 2. validate the chain to the anchor and get the verified leaf (custom-claim escape hatch)
    TrustChainValidationResult chain =
        trustChainValidator.validate(List.of(), clientId, opIssuer, -1, -1, maxAgeSeconds);
    JwtClaims leaf = chain.leafEntityStatement();

    // 3. read the custom attestation metadata block
    Map<String,Object> metadata = Claims.optionalMap(leaf, "metadata");
    Map<String,Object> att = Claims.optionalNestedMap(metadata, "oauth_client_attestation");
    if (att.isEmpty()) throw IssuanceException.invalidClient("entity publishes no oauth_client_attestation metadata");

    // 4. build the same typed config the servlet already consumes
    return AttestationIssuanceConfig.fromEntityMetadata(clientId, att);
}
```

Constructed with a `TrustChainValidator` + `HttpTrustControllerGateway` + `opIssuer` (exactly
`FederationAttesterKeyResolver`'s dependencies) — reused as-is, including their `SubordinateStatementCache`.

## `AttestationIssuanceConfig` — one small refactor

Today `AttestationIssuanceConfig.fromProperties(Map<String,String>)` parses **stringly-typed** extended
properties (JSON-in-a-string for the bundle/instances). Federation metadata arrives as **native**
maps/lists. Extract the current parser's core to accept typed inputs, then add a second factory:

- `fromProperties(Map<String,String>)` — unchanged (PF extended-property path; strings → parse).
- `fromEntityMetadata(String issuerFallback, Map<String,Object> attMeta)` — **new**; takes the native
  `oauth_client_attestation` map (bundle is already a JWKS map, `instances` already a list of maps). No
  JSON-string parsing.

Both produce the identical `AttestationIssuanceConfig` (`bundleKeys()`, `bindings()`, `issuer()`,
`ttlSeconds()`, `clientCeiling()`, `bindingFor(spiffeId)`), so **everything downstream in the servlet is
untouched**.

## Attester identity & signing

Federation publishes **who** the attester is (`attester` = the home attester's `entity_id`, which becomes
the minted `iss` and the required SVID `aud`). It does **not** publish the attester's *private* key. The
servlet keeps signing through the existing
[`AttesterSigningKey`](../com/pingidentity/ps/oidf/common/AttesterSigningKey.java) seam (OpenBao transit /
inline), now keyed by the **resolved attester issuer** rather than a per-client extended property — i.e.
an environment-level map `attester_issuer → transit key`. The attester's *public* key is what the AS-side
verifier resolves via `FederationAttesterKeyResolver` (the deferred verify path) — so issuance and
verification meet at the attester's federation entity: private key in OpenBao, public key in its
`oauth_client_attester.jwks`.

## Trust & security model

- **Membership/status** — the trust controller vouches (or stops vouching) for the client entity;
  `gateway.resolveEntity(clientId)` is the live gate (cache disabled on the controller → immediate
  revocation). This replaces `Client.isEnabled()`.
- **The SPIFFE↔entity binding is asserted by the entity itself** in chain-validated, self-signed metadata,
  and trusted because the entity is a federation member. The SVID (trust-domain-signed) proves the
  SPIFFE ID; the entity's published `instances` authorize it. No party can bind a SPIFFE ID it doesn't
  publish, and no published binding is honored unless the entity currently resolves to the anchor.
- **`aud`** — the SVID must be addressed to the attester (`att.attester`), unchanged from today.

## Caching

Chain resolution is network-bound and attestations mint often. Reuse the validator's
`SubordinateStatementCache` (already there), and add a short-TTL cache of the built
`AttestationIssuanceConfig` keyed by `entity_id` (e.g. 60 s) — entity metadata changes rarely, and
revocation still bites within the TTL because `resolveEntity` is checked per request (or the cache TTL is
kept short). Decision below.

## Wiring & coexistence

The servlet already swaps resolvers (`setClientResolver` / `defaultClientResolver`). Options:

- **Select by config** — `defaultClientResolver()` returns one resolver based on an init-param/env
  (`oidf.issuance.resolver = pf | federation | cimd`).
- **Composite** (recommended) — pick by `client_id` shape, first to yield an `oauth_client_attestation`
  config wins:
  1. **Federation** — the `client_id` resolves to the trust anchor (a signed entity chain). Highest
     assurance, tried first.
  2. **CIMD** — else an `https://…` `client_id` that returns a matching CIMD document.
  3. **PF extended properties** — else a locally-provisioned client (non-URL `client_id`s, e.g. the demo's
     `demo-attest-*`).

The composite keeps the demo working, makes federation and CIMD additive, and encodes the assurance
ordering (chain-vouched before self-asserted).

## Reused vs new

| Reused (no change) | New |
|---|---|
| `TrustChainValidator.validate(...)` → `TrustChainValidationResult.leafEntityStatement()` | `FederationIssuanceClientResolver` (the resolver) |
| `HttpTrustControllerGateway.resolveEntity` / fetch / `SubordinateStatementCache` | `AttestationIssuanceConfig.fromEntityMetadata(...)` (+ extract shared core) |
| `Claims.optionalMap` / `optionalNestedMap` | the `oauth_client_attestation` metadata type (published by entities) |
| `IssuanceClientResolver` seam + `AttestationIssuanceServlet` (SVID/proof/mint) | env-level `attester issuer → signing key` map in `AttesterSigningKey` |
| `AttesterSigningKey` (transit/inline signing) | `CimdIssuanceClientResolver` + its SSRF/5 KB/`client_id`-match guards |
| `HttpGetClient`/`JdkHttpGetClient` (for the CIMD `GET`) | attester **trust-domain → bundle** map (CIMD's non-self-asserted bundle) |
| `FederationAttesterKeyResolver` (template, and the verify-side counterpart) | `fromEntityMetadata`'s `trustBundleFromMetadata` flag; *(deferred)* reverse lookup |

## CIMD — a third, lighter source (draft-ietf-oauth-client-id-metadata-document)

Same metadata contract, **no trust controller**. The `client_id` is itself an **HTTPS URL**; the attester
`GET`s it and reads the `oauth_client_attestation` block straight out of the returned JSON
client-metadata document. It reuses `AttestationIssuanceConfig.fromEntityMetadata(...)` unchanged — only
acquisition and trust differ.

`CimdIssuanceClientResolver implements IssuanceClientResolver`:

1. **Validate the `client_id` URL** (draft §): `https` scheme, has a path, no single/double-dot segments,
   no fragment, no userinfo, SHOULD have no query. **SSRF guard:** https-only, and refuse any host that
   resolves to a private/loopback address.
2. **`GET client_id`** (`Accept: application/json`), **cap the body at 5 KB**, parse JSON. Don't cache
   error/malformed responses.
3. **Confirm `document.client_id == client_id`** (exact string match, required by the draft).
4. Read the `oauth_client_attestation` block → `AttestationIssuanceConfig.fromEntityMetadata(client_id, att)`.
5. **Cache** per RFC 9111 cache headers, with attester-set min/max bounds.

Reuses `HttpGetClient`/`JdkHttpGetClient` (already in the module) for the fetch.

### The trust difference is the whole point

| Source | How config is obtained | What vouches for it | Assurance |
|---|---|---|---|
| **PF extended properties** | local PF client record | the PF operator provisioned it | operator-controlled |
| **OpenID Federation** | signed entity statement, chain-validated to the anchor | the **trust anchor** (membership + revocation) | high — cryptographic chain |
| **CIMD** | plain JSON `GET` of the `client_id` URL | **TLS + control of the URL** (unsigned) | low — self-asserted |

**Critical for CIMD — never trust a self-asserted SPIFFE bundle.** A federation entity's
`spiffe_trust_bundle` is safe to read because the anchor vouches for the chain. A CIMD document is
*unsigned*, so anyone who controls a URL could publish a bundle they also control and mint SVIDs for any
SPIFFE ID. So under CIMD the **bundle is NOT taken from the client document** — the attester holds a
configured **trust-domain → bundle** map (the SPIFFE trust domains it serves), and the CIMD document may
only assert its `instances` (which `spiffe_id`s are its, plus entitlement/metadata). Each claimed
`spiffe_id`'s trust domain must be in the attester's allowlist, and its SVID is verified against the
*attester's* bundle for that domain — which the URL-controller cannot forge.

So `fromEntityMetadata` grows one flag — `trustBundleFromMetadata`: **true** for federation (chain-vouched
bundle in metadata), **false** for CIMD (ignore any bundle in the doc; use the attester's configured
bundle for the SVID's trust domain). Same downstream validation either way.

## Where it lives

The **federation** resolver belongs in the **`openid-federation` carve-out** (canonical home of
`TrustChainValidator` / `ClientEntityAuthorizer` / `HttpTrustControllerGateway`), wired into
`AttestationIssuanceServlet` here. The **CIMD** resolver has no trust-chain dependency (just an HTTP
`GET` + JSON), so it can live in this module (or the `client-attestation` carve-out) alongside
`AttestationIssuanceConfig`. `AttestationIssuanceConfig` stays here; only the shared `fromEntityMetadata`
factory is added.

## Open decisions

1. **Metadata type name** — `oauth_client_attestation` (proposed) vs folding the fields into the standard
   `oauth_client` block. A dedicated type keeps standard metadata clean; pick one.
2. **Cache TTL vs. per-request `resolveEntity`** — always probe membership live (safest revocation) and
   cache only the parsed metadata, or cache the whole config for N seconds (fewer round-trips, slower
   revocation). Recommend: live `resolveEntity` + short metadata cache.
3. **Bundle source** — inline `spiffe_trust_bundle` in client metadata (Phase 1) vs a trust-domain
   federation entity that publishes it (removes per-client bundle duplication; Phase 2).
4. **Coexistence** — composite (recommended) vs config-select.
5. **CIMD bundle trust** — confirm the model: CIMD documents assert only `instances`; the SPIFFE bundle
   comes from the attester's configured **trust-domain → bundle** allowlist (not the self-asserted doc).
   Where is that allowlist configured — env/init-param, or itself resolved from trust-domain federation
   entities (ties to decision 3)?

## Not in this design (Phase 2)

- **`spiffe_id → entity_id` reverse resolution** for SVID-only requests — a genuinely new Lighthouse
  surface (index entities by their published `instances`, or a registry), plus the first-request
  bootstrapping. Only needed if the workload must *not* carry its `entity_id`.
- **AS-side verification** of these federation-minted attestations (the `FederationAttesterKeyResolver`
  verify path is where issuance and verify converge) — already tracked as a follow-up.
