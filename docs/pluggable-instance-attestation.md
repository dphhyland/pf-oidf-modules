# Design — pluggable instance attestation (SPIFFE, wallets, …)

Generalises the hosted attester's **instance-identity layer** from SPIFFE-only into a pluggable format.
A workload proves *what instance it is* with whatever attestation fits its runtime — an infrastructure
workload a **SPIFFE JWT-SVID**, a digital wallet a **Wallet Instance Attestation (WIA)** issued by its
wallet provider — and the attester validates it through the right implementation, then mints exactly as
before. Companion to [attestation-issuance.md](attestation-issuance.md) and
[federation-backed-issuance.md](federation-backed-issuance.md).

**Two orthogonal axes.** Issuance takes trust at two independent levels:

- the **client** layer — *which client is this, and what may it be granted?* — resolved by an
  [`IssuanceClientResolver`](../com/pingidentity/ps/oidf/common/IssuanceClientResolver.java) (PF config /
  OpenID Federation / CIMD). That is the subject of the federation-backed doc.
- the **instance** layer — *which running instance is asking, and in what form does it prove that?* — the
  subject of **this** doc.

SPIFFE was hard-wired as the whole instance layer. It is really one *format*. This change makes the layer
an interface with SPIFFE as the first implementation and a wallet WIA validator as the second.

## The abstraction

Two new types, plus a registry:

- [`InstanceIdentity`](../com/pingidentity/ps/oidf/common/InstanceIdentity.java) — the format-neutral
  result of validating an instance attestation: `format` (→ `workload.attested_by`), `subject` (matched
  against the client's `attestation_instances`), `trustDomain` (the format's trust root), optional
  `boundKey` (a key the attestation itself binds — see below), and `workloadClaims` (format-specific
  members embedded under the minted `workload`).
- [`InstanceAttestationValidator`](../com/pingidentity/ps/oidf/common/InstanceAttestationValidator.java) —
  `InstanceIdentity validate(String presented, AttestationIssuanceConfig config)`. One implementation per
  format.
- [`InstanceAttestationValidators`](../com/pingidentity/ps/oidf/common/InstanceAttestationValidators.java) —
  the registry the servlet holds; `select(declaredFormat, presented)` routes a request to a validator
  (explicit `instance_attestation_format` wins; otherwise the format is sniffed from the token). Selection
  only routes — the chosen validator still fully verifies.

The downstream steps — binding match on `subject()`, instance-key proof, entitlement ceiling, mint+sign —
never change. That is the whole point: the instance layer is swappable without touching the client layer or
the minting pipeline.

## Implementations

### SPIFFE — `SpiffeInstanceAttestationValidator`

A thin adapter over the unchanged
[`SpiffeSvidValidator`](../com/pingidentity/ps/oidf/common/SpiffeSvidValidator.java): validate the SVID
against `config.bundleKeys()` with `config.issuer()` as the required audience, then
`InstanceIdentity.ofSpiffe(svid)` — `format="spiffe"`, `subject=spiffe_id`, `workloadClaims={spiffe_id,
svid}`, no `boundKey` (an SVID binds no key). Byte-identical output to before.

### Wallet — `WalletInstanceAttestationValidator`

A digital wallet has no SPIFFE identity. Its **Wallet Provider** issues each install a **Wallet Instance
Attestation** — a signed JWT binding the wallet instance's key to a known provider. Presenting a WIA is an
OpenID4VC / HAIP requirement, and a WIA *is itself* an OAuth attestation-based client attestation (the same
draft this endpoint mints) — so the instance-proof and our output share a format, and the wallet provider's
trust root resolves through the very same machinery as any attester.

The WIA is a compact JWS:

```jsonc
// header: { "alg": "ES256", "typ": "wallet-instance-attestation+jwt", "kid": "wp-1" }
{
  "iss": "https://wallet.example.com",     // the wallet provider — the trust root (≈ a SPIFFE trust domain)
  "sub": "urn:wallet:instance:abc123",     // the wallet instance id — matched against attestation_instances
  "aud": "https://attester.example.com",   // this attester
  "cnf": { "jwk": { /* the wallet instance's public key */ } },
  "exp": 1893456000
}
```

Validation, in order: header `typ` is a WIA type (or absent) and `alg` is asymmetric; `iss`/`sub` present;
the wallet provider is **trusted** — its keys are resolved via an
[`AttesterKeyResolver`](../com/pingidentity/ps/oidf/common/AttesterKeyResolver.java) (see below) and the
signature verifies under the `kid`-selected key; `exp` not past (small skew); `aud` names this attester; an
optional per-client **provider pin** (`expectedTrustDomain`) must equal `iss`; and `cnf.jwk` is a public
key. Result: `format="wallet"`, `subject=sub`, `trustDomain=iss`, `boundKey=cnf.jwk`,
`workloadClaims={wallet_provider, wallet_instance, instance_attestation}`. Any failure →
`invalid_instance_attestation`.

**Trust root resolution reuses the attester machinery.** The wallet-provider key resolver is an
`AttesterKeyResolver` — the same interface
[`FederationAttesterKeyResolver`](../com/pingidentity/ps/oidf/common/FederationAttesterKeyResolver.java)
implements. In production a wallet provider is an OpenID Federation entity (`openid_wallet_provider`),
resolved by trust chain; for dev,
[`StaticAttesterKeyResolver`](../com/pingidentity/ps/oidf/common/StaticAttesterKeyResolver.java) trusts a
configured provider→JWKS map. No new trust plumbing.

### `boundKey` — the one new endpoint check

A SPIFFE SVID names no key; a WIA's `cnf` *does*. When `InstanceIdentity.boundKey()` is non-null the servlet
requires it to equal the presented `instance_key` (RFC 7638 thumbprint match) — so the WIA being consumed is
about *this* key, not some other. The instance-key proof-of-possession (unchanged) still proves live
possession. SPIFFE returns `boundKey == null`, so the check is a no-op there. This keeps the flow uniform:
one code path, format-specific behaviour expressed as data.

## Request shape

Back-compatible. The existing `svid` field still works (→ SPIFFE). New optional fields:

- `instance_attestation` — the compact attestation of any format;
- `instance_attestation_format` — an explicit `"spiffe"` | `"wallet"` (else sniffed).

The presented value is `instance_attestation` if given, otherwise `svid`.

## Config

`attestation_instances` entries accept `spiffe_id`, `subject`, or `wallet_instance` as the instance id (all
populate the same format-neutral `subject`). The SPIFFE trust bundle
(`attestation_spiffe_bundle`) is now **optional** — a wallet-only client configures none; a SPIFFE request
to such a client then fails cleanly at SVID validation, and wallet requests never consult it. A client may
pin an accepted wallet provider via `attestation_trust_domain` (reused as the provider pin for the wallet
format).

## Wiring

The servlet's default registry is SPIFFE-always plus the wallet validator when
`OIDF_WALLET_PROVIDER_JWKS` (a provider→JWKS map, trusted statically) is set — opt-in exactly like CIMD. A
federation-backed wallet validator drops into the same registry once its trust-chain wiring is supplied;
inject it via `setInstanceValidators(...)` until then.

## Reused vs new

| Reused unchanged | New |
| --- | --- |
| `SpiffeSvidValidator`, `InstanceKeyProofValidator`, `AttestationMinter` (via an `InstanceIdentity` overload), `RarEntitlement`, `AttesterKeyResolver` / `StaticAttesterKeyResolver`, `Jwks` | `InstanceIdentity`, `InstanceAttestationValidator`, `SpiffeInstanceAttestationValidator`, `WalletInstanceAttestationValidator`, `InstanceAttestationValidators` |

## Security notes

- The wallet provider's keys come from the resolver's **trust decision** (federation chain or static
  allowlist), never from the self-asserted WIA — the same discipline as CIMD's bundle handling.
- `boundKey == instance_key` prevents a caller from presenting a valid WIA for one key while binding a
  different key it doesn't attest.
- The provider pin (`expectedTrustDomain`) lets a client accept only named wallet providers even when the
  resolver would trust a wider federation.

## Status (built)

- `InstanceIdentity`, `InstanceAttestationValidator`, `SpiffeInstanceAttestationValidator`,
  `WalletInstanceAttestationValidator`, `InstanceAttestationValidators` — added; servlet wired to
  select-by-format with the `boundKey` check; config generalised (optional bundle, `subject` /
  `wallet_instance` ids); `invalid_instance_attestation` / `instance_not_authorized` error codes added.
- Tests: `WalletInstanceAttestationValidatorTest` (13), `InstanceAttestationValidatorsTest` (8), extended
  `AttestationIssuanceServletTest` (wallet happy path + `boundKey` mismatch + unbound + untrusted provider),
  SPIFFE/minter/config back-compat suites green.

## Not in this design (later)

- Device attestation validators (Android Key Attestation, Apple App Attest, WebAuthn) — new
  `InstanceAttestationValidator` implementations, no endpoint change.
- Federation-backed wallet-provider resolution wired into the runtime default (mirrors the pending
  federation client-resolver wiring: op issuer + trust controller).
- Sourcing wallet bindings from federation / CIMD metadata (the `oauth_client_attestation` block gains a
  `wallet_instances` / provider-allowlist shape).
