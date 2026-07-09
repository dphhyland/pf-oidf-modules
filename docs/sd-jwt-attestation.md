# Optional SD-JWT attestation encoding

Lets the Client Attestation be sent as an **SD-JWT** (selective-disclosure JWT) instead of a plain JWT, so the
client discloses only the claims a given AS/domain needs — trimming the `workload` claim and revealing only the
relevant `authorization_details` entry rather than the whole attested ceiling. It is an **encoding option under
the same auth methods** (`attest_jwt_client_auth` / `attest_jwt_client_auth_dpop`), not a new method.

## Optionality (backward compatible)
- **Auto-detected:** a plain attestation JWT has no `~`; an SD-JWT presentation is `issuer-jwt~disc~…~`. The
  verifier branches on `~`, so existing plain-JWT clients are unaffected.
- **Config gate:** `attestation_format` = `jwt` (default) | `sd-jwt` | `either`, with a per-client
  `attestation_required_format` extended property so a domain can *require* SD-JWT for sensitive clients.
- **Advertised:** `client_attestation_formats_supported: ["jwt","sd-jwt"]` (provisional) in the OP metadata.

## Claim layout
| Always visible (needed to verify/route) | Selectively disclosable |
|---|---|
| `iss` (attester — resolves the key), `cnf`, `exp`, `iat`, `aud` (the AS), `_sd_alg` | each `workload.*` field; each `authorization_details[*]` entry |
| `sub`/`client_id` (visible by default; SD in a stricter profile) | |

Transport keeps the two-header split:
- `OAuth-Client-Attestation: <issuer-jwt>~<disc1>~<disc2>~` (issuer JWT + chosen disclosures)
- `OAuth-Client-Attestation-PoP` / `DPoP` = the **Key-Binding JWT**

## PoP ⇒ Key-Binding JWT
Our PoP already *is* a KB-JWT except for `sd_hash`:

| PoP today | KB-JWT |
|---|---|
| `aud` | `aud` |
| `challenge` | `nonce` |
| `iat`, `jti` | `iat` |
| — | **`sd_hash`** = SHA-256 over the presented SD-JWT+disclosures (binds the exact disclosures) |
| signed by `cnf` key | same |

`typ` `oauth-client-attestation-pop+jwt` → `kb+jwt`. In DPoP combined mode the DPoP proof carries `sd_hash` too.

## Verifier flow (SD-JWT is a front-end transform)
```
if attestation header contains '~':
    Parsed p = SdJwt.parse(header)                 # issuer-jwt, disclosures, (kb-jwt in the PoP/DPoP header)
    verify attester signature over p.issuerJwt     # unchanged: JwtCodec + FederationAttesterKeyResolver
    ClientAttestation att = ClientAttestation.fromSdJwt(issuerClaims, p.disclosures, raw)  # reconstruct + checks
    verify KB-JWT: sd_hash matches, aud, nonce == challenge, signed by cnf   # == existing PoP key-binding
else:
    (current plain path)
```
Everything downstream — cnf binding, replay, containment — runs on the reconstructed `ClientAttestation`
unchanged. `result.entitledAuthorizationDetails()` then carries only the disclosed entries, so the RAR bridge
and the PingAuthorize plugin forward *those* (PDP checks `requested ⊆ disclosed`) with **no change**.

## Status
- **Done:** `SdJwt` + `SdJwtException` + `ClientAttestation.fromSdJwt(...)`; the verifier `~`-detection branch
  (verify issuer signature → reconstruct → build attestation) + KB-JWT `sd_hash` binding; and the
  `ClientAttestationConfig.acceptSdJwt` / `requireSdJwt` knobs. Tests: `SdJwtTest`, `ClientAttestationSdJwtTest`,
  `ClientAttestationVerifierSdJwtTest` (5 e2e cases — only the disclosed entitlement surfaces; wrong/missing
  `sd_hash` rejected; format policy enforced). Additive — plain path + its tests untouched (pf-oidf-modules 59 green).
- **Surfaced as an option:** advertised via `client_attestation_formats_supported` in the OP metadata
  (`AttestationMetadataConfig` + `FederationService`); selectable per client via the `attestation_format`
  extended property (`ClientAttestationUtils.buildConfig`: `jwt` | `sd-jwt` | `either`); and shown in the demo UI
  (`harness/ui/index.html`) via a Plain-JWT / SD-JWT toggle that mints a real SD-JWT and displays which claims
  are disclosed vs withheld. Documented in the top-level `README.md`.
- **Remaining (cosmetic):** switch the KB-JWT `typ` to `kb+jwt` (kept `oauth-client-attestation-pop+jwt` +
  `sd_hash`, which already provides the binding).

## Scope line
SD-JWT gives **claim minimization + ceiling privacy**; it is **not** unlinkable (the attester `iss` is revealed
and the signature correlates across shows). Pair it with **per-audience, short-lived issuance** (fresh SD-JWT +
`aud` per domain) for cross-domain unlinkability. BBS is only warranted for a long-lived, multi-show credential.
