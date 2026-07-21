# Related repos — the ecosystem and overlap map

Where `pf-oidf-modules` sits among the other repos on this machine / on GitHub, what overlaps with
what, and which repo is the source of truth for each piece. Verified against the actual working trees
(2026-07-19); companion to [REPO-MAP.md](REPO-MAP.md).

## Lineage at a glance

```
idp-ping-rar-plugin (2024, au.com.idpartners RAR)          ── conceptual ancestor of pf-rar-paz-plugin

pf-oidf-modules  (THIS REPO — the monolith: com.pingidentity.ps.oidf.*, harness/ui, deploy/*)
   │  copy-based extraction along module seams (see pf-oidf/MIGRATION.md)
   ├─► pf-oidf                (multi-module Maven rebuild — intermediate staging, local only)
   └─► standalone Apache-2.0 repos:
         oidf-jose ─► client-attestation ─► openid-federation ─► pf-integration (builds oidf.war)
                              ▲
   client-attestation-sdk ─► client-attestation-sdk-polyglot   (client/builder side; paired, not same source)

pf-oidf-modules build artifacts (jar / oidf.war / RAR plugin / mock-attesters / data.zip) consumed by:
   ├─► idp-paz-authzen-adapter/demo/pingfederate    (the agentic PF deploy — what prod actually runs)
   └─► idp-pingfed-ssf-servelet/pf                  (the SSF demo compose)

idp-scim-service (Identity Object Model, idm.entry schema) ── schema contract consumed by:
   ├─ pf-oidf-modules  ssf/LdmSsfStore  (storeDialect=ldm)
   └─ idp-pingfed-ssf-servelet/ldm-store (byte-identical copy of the model DDL)
```

## The clean-room carve-out family (dphhyland/*, Apache-2.0)

A copy-based split of this repo along module seams — **same `com.pingidentity.ps.oidf.*` package and
class names**, so any class in both places is a potential drift hazard.

| Repo | Role | Overlapping classes (same package+name) | Freshness signal |
|---|---|---|---|
| `pf-oidf` (local only) | Intermediate multi-module rebuild; its `MIGRATION.md` documents the extraction | whole servlet/attestation seam | last commit 2026-07-05 |
| `oidf-jose` | Foundation JOSE module | `common.{JwtCodec, Jwks, Claims, SdJwt, SdJwtException, HttpGetClient, JdkHttpGetClient, SigningKeyProvider}` | 2026-07-07 |
| `client-attestation` | AS-side attestation verifier | `common.{ClientAttestationVerifier, ClientAttestationConfig, DpopProof, DpopProofValidator, AttesterKeyResolver, MiniRedisClient, RarEntitlement}` + challenge/replay machinery | 2026-07-07 |
| `openid-federation` | Federation module | `common.{TrustChainValidator, TrustChainValidationResult, ClientEntityAuthorizer, HttpTrustControllerGateway, TrustControllerGateway, SubordinateStatementCache}`, `servlet.trustanchor.{FederationService, FederationConfiguration}` | 2026-07-10 — **ahead of this repo** (advertises draft-10 `client_attestation_pop_methods_supported`) |
| `pf-integration` | The PF-glue module (only one with the PF SDK dep); assembles `oidf.war` | `servlet.clientregistration.*` (registration servlet + service + config), `utils.OIDFederationUtils`, `servlet.trustanchor.OpenIdFederationServlet`, `common.{PfMgmtClientStore, PfJwksSigningKeyProvider, ClientStore}` | 2026-07-07 |

**Source-of-truth calls:**
- The CFR-derived federation/registration classes: **the carve-outs are canonical** (this repo
  deliberately gitignores its CFR copies; `ClientEntityAuthorizer`'s canonical home is the carve-outs).
- The attestation/JOSE `common` classes exist tracked in **both** — treat the carve-outs as canonical
  going forward and back-port only deliberately; `openid-federation` is already ahead on the federation
  spec, so new federation work belongs there.
- Everything with **no carve-out counterpart is canonical here**: the whole SSF transmitter + receiver
  (`ssf/` + `servlet/ssf/`), `pf-rar-paz-plugin`, the deploy contexts + Terraform, the harness/demo UI.

> ⚠️ **Note (verify intent):** `pf-integration` and the other carve-outs are **public** on
> `github.com/dphhyland`, though the CI workflow here treats `pf-integration` as private (deploy key).
> If the split's purpose was keeping the PF-glue private, that's worth revisiting.

## Client-side counterparts (paired, not overlapping source)

- `client-attestation-sdk` — the attestation **builder** (client/attester side). Archived; moved into…
- `client-attestation-sdk-polyglot` — Java/Python/TS/Go builders + SPIRE deploy; round-trip tested
  against the `client-attestation` verifier. The vendored `client_attestation_sdk` used by
  `harness/agent-workload/` comes from this lineage. Shares the wire protocol and Maven groupId, not code.

## Integration / demo consumers

| Repo | Relationship | Evidence |
|---|---|---|
| `idp-paz-authzen-adapter` (ID-Partners, very active) | **Downstream consumer** — its `demo/pingfederate/` COPYs this repo's built artifacts (`pf-oidf-modules.jar`, `oidf.war`, `pf.plugins.pf-rar-paz-plugin.jar`, `oidf-mock-attesters.json`, `data.zip`, master-key overlay) into a stock PF image. This **agentic context is what live prod (`pingfederate-runtime`) actually runs** — not this repo's minus-RAR `deploy/pingfederate/`. Artifacts are rebuilds, not byte-copies (jar checksums differ), so version skew is possible; discriminate deployed builds by jar size / `unzip -l`. Its `demo/attester/` is the mock attestation-signing service paired with our pre-trusted `oidf-mock-attesters.json`. |
| `idp-pingfed-ssf-servelet` (local) | **Downstream demo** of this repo **and** `idp-scim-service`: docker-compose proving the SSF transmitter **and receiver** end-to-end on real PF + real Postgres (incl. the loopback RFC 8935 push → receiver → grant-revocation stage). Also **deployed publicly on Railway** (project `ssf-demo`): UI at `ssf-demo-ui-production.up.railway.app`, transmitter at `pingfederate-ssf-production.up.railway.app`. Carries a prebuilt module jar and a byte-identical copy of the Identity Object Model DDL. |
| `idp-scim-service` (local, "ldm-copilot") | **Upstream data model**: owns `spec/identity-object-model.sql` (the `idm.entry` Postgres/JSONB object-class store) and the governed migration workflow. Our `LdmSsfStore` (dialect `ldm`) writes `ssfStream`/`ssfStreamSubject`/`ssfPendingSet` entries against that schema; the SSF classes are registered there by `migrations/0001-add-shared-signals-ssf.sql`. A schema contract, not shared Java — schema changes must go through that repo's review workflow. |
| `idp-ping-rar-plugin` (ID-Partners, 2024) | **Conceptual ancestor only**: an older RAR `AuthorizationDetailProcessor` (`au.com.idpartners.…RARAuthDetailsProcessor`, PF 12.1.3). `pf-rar-paz-plugin` re-implements the same PF extension point under the oidf namespace with real decisioning; no shared source. |

## Scanned and found independent

`idpartners-authzen-pingfed-plugin` (separate AuthZen PF plugin, `au.com.idpartners` namespace),
`idp-pf-simplifid` (PF config-as-code; references this repo only in research notes), `idp-pf-vcs`
(design docs), `idp-gm-api` (its `gm-api.war` is bundled by idp-paz-authzen-adapter, not by us), and
the CDR/FAPI/MATTR/AuthZen repos — no shared `com.pingidentity.ps.oidf` code or artifacts.

## Drift hazards to keep in mind

1. **Same-named classes in the carve-outs** — a fix landed here won't reach `oidf.war` built from
   `pf-integration` (and vice versa) unless ported. The CI build-in-CI scaffold builds from the
   carve-out, while local deploys build from this repo: **two build paths for the "same" module.**
2. **Two PF deploy contexts** — this repo's `deploy/pingfederate/` (OIDF-only, minus-RAR) vs
   `idp-paz-authzen-adapter/demo/pingfederate/` (agentic, what prod runs). They share artifact names
   but not content; the agentic context has also drifted ahead of live prod (adds
   `urn:agent:northwind-autonomous:v1`, RAR plugin jar, MFA kit).
3. **The Identity Object Model schema** — three copies exist (idp-scim-service spec, its migration,
   the demo's init copy); idp-scim-service is canonical.
