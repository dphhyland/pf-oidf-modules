# Attestation issuance endpoint

Lets a running workload **earn** a Client Attestation by proving who it is, rather than the attestation
being a pre-shared artifact. A SPIFFE workload presents its **JWT-SVID** and receives a freshly-minted
Client Attestation bound to its own instance key, which it then uses to authenticate at the AS token
endpoint via the existing `attest_jwt_client_auth` path.

This is the **hosted attester** — the issuance counterpart to the verification the module already does. It
is **issuance only**: the AS-side verification / client-authentication path is unchanged.

`AttestationIssuanceServlet` → `POST /federation/attestation`.

**See it / ship it:** the demo's **③ Attestation minting** tab walks the whole flow (SVID → mint → decode)
with an inline-JWK vs OpenBao-transit toggle (`harness/ui/`). To deploy against a live PF, follow
[../deploy/pingfederate/DEMO-MINT-DEPLOY.md](../deploy/pingfederate/DEMO-MINT-DEPLOY.md).

## Roles (three keys, not one)

The client does **not** sign the attestation. Three distinct parties/keys are involved:

| Party | Key | Signs | Artifact |
|---|---|---|---|
| **Attester** (this endpoint) | the client's per-client attester key | the **attestation** | `oauth-client-attestation+jwt` |
| **Workload / instance** | the instance key (`cnf`) | the **instance-key proof** (here) and later the **PoP** (at the token endpoint) | `oauth-attestation-instance-proof+jwt` / `oauth-client-attestation-pop+jwt` |
| **SPIRE / trust domain** | the trust-domain JWT authority | the **JWT-SVID** | SPIFFE JWT-SVID |

## Two tokens, two lifetimes

The workload's **instance key lives for the whole life of the workload** and never leaves it — it is not
re-fetched. Only the **attestation** that vouches for that key is refreshed, by re-calling this endpoint
with the workload's next fresh SVID. The attestation TTL (`attestation_issued_ttl`, default 300 s) is a
**revocation lever**: a short TTL means deauthorizing a SPIFFE ID cuts the workload off within minutes
(re-attestation fails), with no per-request revocation check at the token endpoint. It rides SPIRE's own
SVID rotation (~10 min).

## Flow

```
workload                         /federation/attestation
   |  (optional) GET /federation/attestation-challenge  -> challenge          (reused, existing servlet)
   |  POST { client_id, instance_key(public JWK), svid, proof, authorization_details? }
   |------------------------------------------------------------------------->|
   |   1. load client + status gate (Client.isEnabled(); trust-controller later)
   |   2. validate SVID vs the client's SPIFFE bundle (kid, ES256, aud∋issuer, exp, trust domain)
   |   3. match the SVID's SPIFFE ID to one of the client's bindings
   |   4. verify the instance-key proof (signature by instance_key, aud=issuer,
   |         challenge-consume + jti replay via the shared AttestationSupport store)
   |   5. enforce the RFC 9396 entitlement ceiling (requested ⊆ effective)
   |   6. mint + sign with the per-client attester key
   |<-- 200 { "attestation": "<jwt>", "expires_in": N }   (Cache-Control: no-store)
```

Minted claims: `iss`=attester, `sub`=`client_id`, `iat`, `exp`, `cnf.jwk`=the instance key,
`workload`=`{attested_by:"spiffe", spiffe_id, attributes:<binding metadata>, svid:<raw>}`,
`authorization_details`=the granted entitlement, `typ`=`oauth-client-attestation+jwt`.

On failure a JSON body `{"error","error_description"}` with a stable code: `invalid_request`,
`invalid_client`, `invalid_svid`, `spiffe_id_not_authorized`, `invalid_instance_proof`, `access_denied`,
`server_error`.

## One client → many SPIFFE IDs (with per-instance metadata)

Registration binds a **list** of SPIFFE IDs to a client via `attestation_instances`; each entry may carry
its own `entitlement` (an RFC 9396 ceiling for that instance, which must sit within any client-level
ceiling) and `metadata` (attributes carried into the attestation's `workload.attributes`, held for future
per-instance enforcement at token time):

```json
[
  { "spiffe_id": "spiffe://banking.demo/payment-agent",
    "entitlement": [ { "type": "sales_agent", "sales_regions": ["EMEA"] } ],
    "metadata": { "region": "EMEA", "environment": "prod" } },
  { "spiffe_id": "spiffe://banking.demo/reporting-agent" }
]
```

## Signer: per-client, config-chosen

The attester key is **per client** and its backing is a configuration choice, behind a `JwsSigner` seam:

- `attestation_signing_key_ref` → **OpenBao transit** (`OpenBaoTransitSigner`) — the private key never
  enters PF's JVM; PF signs via `POST /v1/transit/sign` with `marshaling_algorithm=jws`. The vault
  address/token are environment-level (`openBaoUrl`/`openBaoToken` init-params, else `OIDF_OPENBAO_URL` /
  `OPENBAO_ADDR` / … and `OIDF_OPENBAO_TOKEN` / `OPENBAO_TOKEN` / …). Give PF a **least-privilege token** —
  a policy allowing only `read`+`sign` on that one transit key, not the vault root token.
- `attestation_signing_jwk` → an **inline private JWK** (`LocalJwkSigner`, EC or RSA) — simple for
  dev/demo.

Set exactly one.

## Configuration

Servlet init-params: `challengeRequired` (default `false`), `openBaoUrl`, `openBaoToken`.

Per-client **extended properties** (provisioned at registration / admin / Terraform):

| Property | Meaning |
|---|---|
| `attestation_issuer` | Attester identity: `iss` of minted attestations **and** the required SVID `aud` |
| `attestation_spiffe_bundle` | SPIFFE trust-bundle JWKS used to verify presented SVIDs |
| `attestation_signing_key_ref` *or* `attestation_signing_jwk` | Attester key — transit key name **or** inline private JWK (exactly one) |
| `attestation_instances` | JSON array of SPIFFE-ID bindings (`spiffe_id` + optional `entitlement` + `metadata`) |
| `attestation_entitlement` | Optional client-level RFC 9396 ceiling; per-instance entitlements must be ⊆ this |
| `attestation_issued_ttl` | Lifetime (s) of the minted attestation (default `300`) |
| `attestation_trust_domain` | Optional: pin the accepted SVID trust domain |

## Standards

| Component | Standard |
|---|---|
| Client Attestation JWT | draft-ietf-oauth-attestation-based-client-auth |
| `cnf.jwk` proof-of-possession binding | RFC 7800 |
| JWS / JWT / `kid` thumbprint | RFC 7515 / 7519 / 7638 |
| `authorization_details` | RFC 9396 |
| JWT-SVID + trust bundle | SPIFFE JWT-SVID + Trust Domain & Bundle |
| OpenBao transit ECDSA marshaling | JOSE ECDSA (RFC 7518 §3.4) |

## Status

- **Done:** `AttestationIssuanceServlet` + `SpiffeSvid`/`SpiffeSvidValidator`,
  `InstanceKeyProofValidator`, `AttestationIssuanceConfig`/`SpiffeBinding`, `AttestationMinter`,
  `JwsSigner`/`OpenBaoTransitSigner`/`LocalJwkSigner`/`AttesterSigningKey`, `IssuanceClientResolver` (seam)
  + `PfIssuanceClientResolver` (runtime). 48 JUnit tests (servlet at 100% instruction), including a
  round-trip that verifies the minted attestation through the existing `ClientAttestationVerifier`
  (inline and vault signers). Harness: `harness/run.sh issuance-selfverify`.

## Scope & forward compatibility

Issuance only — the AS-side **verification** of these attestations is deliberately untouched. Two seams
keep it forward-compatible with the OpenID-Federation / trust-controller end state:

- **Client status / authorization** — today `Client.isEnabled()`; later the trust controller's
  `ClientEntityAuthorizer` (membership + policy + status).
- **SPIFFE bundle source** — today an inline JWKS extended property; later the client entity's federation
  metadata. The validator is unchanged; only the source moves.

The **"home" attester** then becomes a federation entity — private key already external in OpenBao, public
key published in its entity statement, resolved by the AS over the trust chain via
`FederationAttesterKeyResolver`. Remaining follow-ups: teach the verifier to trust per-client attester
keys and enforce per-instance `workload.attributes` / entitlements at token time; a registration API to
provision the `attestation_*` properties; bundle-by-URL (the SPIRE `jwks.json`) with refresh.
