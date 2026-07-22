# Repo map — what is in pf-oidf-modules

A single Maven/Java project carrying **five distinct capabilities** for PingFederate, plus the
config-as-code deploy surface for the whole Railway environment and the demo/verification tooling.
Companion doc: [RELATED-REPOS.md](RELATED-REPOS.md) — how this repo relates to (and overlaps with) the
other repos in the ecosystem.

The five capabilities:

1. **OAuth 2.0 Attestation-Based Client Authentication** — the *verification* side
   (`attest_jwt_client_auth` + DPoP combined mode; plain attestation JWTs — the SD-JWT encoding was
   dropped, its canonical home is the `oidf-jose` carve-out) — `common/` + the challenge servlet + OGNL hooks.
2. **Attestation issuance — the hosted attester** (`AttestationIssuanceServlet` → `/federation/attestation`):
   a SPIFFE workload exchanges its JWT-SVID for a minted Client Attestation bound to its instance key,
   signed by the client's per-client attester key (OpenBao transit or inline JWK). See
   [attestation-issuance.md](attestation-issuance.md).
3. **OpenID Federation 1.0** — trust-anchor/entity/fetch/list/resolve servlets, explicit (§12.2) and
   transparent automatic (§12.1) client registration.
4. **SSF 1.0 transmitter + receiver** (Shared Signals: CAEP + RISC Security Event Tokens, both
   directions) — `ssf/` + `servlet/ssf/`.
5. **RAR → PingAuthorize** (`pf-rar-paz-plugin/`) — a true PF SDK `AuthorizationDetailProcessor` plugin
   bridging RFC 9396 `authorization_details` to a PingAuthorize governance-engine decision.

## Top-level layout

| Path | What it is |
|---|---|
| `com/` | The **tracked source tree** (clean-room classes only — see "Source conventions"). |
| `src/test/java` | The tracked test tree (45 test classes). |
| `src/main/java` | **Gitignored build mirror**, copied from `com/` at build time (plus the CFR-decompiled files and two authored mirror-only classes — see below). |
| `deploy/` | Config-as-code deploy context per Railway service: `pingfederate/` (the AS + Terraform), `lighthouse/` (trust anchor), `fedhost/` (static federation entity host). |
| `docs/` | Per-feature design docs and runbooks (index below). |
| `harness/` | Verification tooling: in-process self-verify harnesses, live probes, the demo UI (`ui/` = the deployed `pf-demo-ui`), and a SPIFFE-attested `agent-workload/` service. |
| `pf-rar-paz-plugin/` | Separate Maven module — the RAR→PingAuthorize PF SDK plugin. |
| `.github/workflows/` | Four Railway push-to-deploy workflows (below). |
| `build-tools/` | ⚠️ Referenced by `pom.xml` (assembly descriptor, PMD/Checkstyle configs) but **not present** — hence `mvn package -Dassembly.skipAssembly=true -Dpmd.skip=true -Dcheckstyle.skip=true`. |

## Source conventions (important)

- **`com/` is the tracked mirror; `src/main/java` is gitignored.** Building requires
  `cp -R com/* src/main/java/com/` (the build tree also holds what git must never publish).
- **Clean-room vs decompiled:** all **103 tracked** classes are clean-room authored. A further **22
  CFR-decompiled PingFederate classes** (headers say `Decompiled with CFR 0.152`) exist only on local
  disk, gitignored — they are Ping IP and are excluded from the public repo by design. One authored
  class (`ClientEntityAuthorizer`) is also gitignored because its canonical home moved to the private
  carve-out repos.
- **Two authored classes exist only in the gitignored mirror** (not in `com/`):
  `RegisteredClientsServlet` and `TokenEndpointAutoRegistrationFilter` — their tests *are* tracked
  (`TokenEndpointAutoRegistrationFilterTest`, `RegistrationServiceAutomaticRegisterTest`). Their
  canonical home is the `pf-integration` carve-out; mirroring them into `com/` is an open tidy-up.
- This repo **does not build standalone from a fresh clone** — the PF SDK jars are `provided` (installed
  locally from a PF distribution, or extracted from the PF Docker image in CI), and the gitignored CFR
  files are needed to compile the federation/registration servlets.

## Package inventory (`com.pingidentity.ps.oidf`)

### `common` — attestation / JOSE / federation primitives (45 tracked)
Client-attestation verification (`ClientAttestation`, `ClientAttestationVerifier`, `ClientAttestationConfig`,
`ClientAttestationResult`, `ClientAttestationException`), attester trust (`AttesterKeyResolver` +
`FederationAttesterKeyResolver` via trust chain / `StaticAttesterKeyResolver` DEV mock), freshness +
replay (`AttestationChallengeService`, `AttestationReplayCache`, in-memory impls, `RedisAttestationStore` +
dependency-free `MiniRedisClient`, `AttestationSupport` singletons), DPoP combined mode (`DpopProof`,
`DpopProofValidator`), JOSE helpers (`Jwks`), and the RFC 9396
entitlement ceiling (`RarEntitlement`).
Attestation **issuance** primitives (see [attestation-issuance.md](attestation-issuance.md)): SVID
validation (`SpiffeSvid`, `SpiffeSvidValidator`), the instance-key proof (`InstanceKeyProofValidator`),
per-client config + one-to-many bindings (`AttestationIssuanceConfig`, `SpiffeBinding`,
`IssuanceClientResolver`, `IssuanceException`), the minter (`AttestationMinter`), and the pluggable
attester signer (`JwsSigner` ← `OpenBaoTransitSigner` / `LocalJwkSigner`, selected by `AttesterSigningKey`).
*Gitignored CFR siblings:* trust-chain validation, PF client store/JWKS access, HTTP gateways
(`TrustChainValidator`, `PfJwksSigningKeyProvider`, `PfMgmtClientStore`, `HttpTrustControllerGateway`, …).

### `servlet/attestation` · `servlet/trustanchor` · `servlet/clientregistration`
- `ClientAttestationChallengeServlet` — `/federation/attestation-challenge` (tracked).
- `AttestationIssuanceServlet` — `/federation/attestation`, the hosted attester: SPIFFE JWT-SVID →
  minted attestation (tracked). `PfIssuanceClientResolver` reads the client + `attestation_*` extended
  properties at runtime. See [attestation-issuance.md](attestation-issuance.md).
- `AttestationMetadataConfig` — attestation entries in OP metadata (tracked).
- `OpenIdFederationServlet` (CFR) — `/.well-known/openid-federation`, `/federation/{entity,fetch,list,resolve}`.
- `OpenIdRegistrationServlet` + registration internals (CFR) — OIDF explicit registration.
- `ClientAttestationUtils` (tracked) — **the OGNL issuance-criteria hook** `validateClientAttestation(#this)`;
  also publishes the attestation context consumed by `pf-rar-paz-plugin`. `OIDFederationUtils` (CFR) —
  the `validateTrustChain(...)` OGNL hook.

### `ssf` + `servlet/ssf` — the SSF 1.0 transmitter + receiver (44 tracked)
See [ssf-transmitter.md](ssf-transmitter.md) for the full design. **Transmitter:** RFC 8417 SET minting
with PF's JWKS key (`SetMinter`), stream management + RFC 8936 poll + RFC 8935 push with
retry/dead-letter (`StreamManagementService`, `PushDeliveryService`), event fan-out (`SsfEventEmitter`,
`SsfEventBridge`, `CaepRiscEvents`), three store backends behind `SsfStore` (`InMemorySsfStore`
per-node dev, `JdbcSsfStore` 3-table durable, `LdmSsfStore` → the ID Partners Identity Object Model),
optional reflective Kafka fan-out (`KafkaSetPublisher`), API auth by PF introspection
(`PfIntrospectionReceiverAuthenticator`), SCIM subject management (`ScimSubjectService`), env/sysprop/
init-param layered config (`SsfConfiguration`, `SsfSupport`). **Receiver:** RFC 8935 push intake with
JWKS verification + `jti` dedup (`SsfReceiverServlet`, `SetVerifier`, `ReceivedSet`,
`SsfReceiverService`), an RFC 8936 poll client (`PollReceiverClient`), remote-stream registration
(`ReceiverStreamClient`), and event-type-dispatched actions — verified `session-revoked` /
`account-disabled` revokes the subject's PF OAuth grants (`ReceiverActionHandler`,
`PfReceiverActions` via `AccessGrantManagerAccessor`).

## HTTP surface

| Endpoint | Class | Notes |
|---|---|---|
| `/federation/attestation-challenge` | `ClientAttestationChallengeServlet` | attestation freshness challenges |
| `/federation/attestation` | `AttestationIssuanceServlet` | issues a client attestation from a SPIFFE JWT-SVID |
| `/.well-known/openid-federation`, `/federation/{entity,fetch,list,resolve}` | `OpenIdFederationServlet` (CFR) | federation trust anchor/leaf |
| `/federation/registered-clients` | `RegisteredClientsServlet` (mirror-only) | demo listing |
| `/.well-known/ssf-configuration` | `SsfConfigurationServlet` | SSF metadata, loadOnStartup |
| `/ssf/streams`, `/ssf/status`, `/ssf/subjects:add`, `/ssf/subjects:remove`, `/ssf/verify` | `SsfStreamManagementServlet` | SSF stream API |
| `/ssf/poll` | `SsfPollServlet` | RFC 8936 |
| `/ssf/scim/v2/Users[/*]` | `SsfScimSubjectServlet` | provisioning → stream subjects |
| `/ssf/receiver/events` | `SsfReceiverServlet` | RFC 8935 push intake (receiver side); GET lists received SETs |
| *(filter)* `/idp/init_logout.openid` | `LogoutEventFilter` | logout → CAEP session-revoked; registered in `pf-runtime.war` web.xml by the assemble script |
| *(filter)* `/as/token.oauth2` | `TokenEndpointAutoRegistrationFilter` (mirror-only) | OIDF §12.1 automatic registration |

Deployment model: the module jar is **merged into `pf-runtime.war`** (root context, single classloader)
by `deploy/pingfederate/build/assemble-pf-runtime-war.sh` — which also registers the logout filter and
deliberately **skips jose4j** (PF ships its own; a second copy causes a LinkageError).

## `pf-rar-paz-plugin` (separate Maven module)

A genuine PF SDK plugin (`PF-INF/authorization-detail-processors` →
`AttestationAwareRarProcessor`): forwards each RAR detail + the client-attestation context to a
PingAuthorize governance-engine decision, denies unless PERMIT, applies returned statements, enforces
requested ⊆ attested, real refresh narrowing. Jackson is **shaded/relocated** to survive PF's
per-plugin classloader. Artifact name `pf.plugins.pf-rar-paz-plugin` (PF discovery convention).
Note: no Terraform resource type exists for authorization-detail processors — its PF config stays an
unmanaged part of `data.zip`.

## Deploy, CI, Terraform

- `deploy/pingfederate/` — the AS image (PF 13.0.3 + this module merged into `pf-runtime.war`,
  OIDF-only `data.zip` via drop-in-deployer, DEV mock attester, **DevOps-fetched licensing** — no baked
  `.lic`). `vars.{staging,production}.env` carry non-secret env config incl. the `OIDF_SSF_*` transmitter
  settings. `terraform/` is the **config-as-code source for the running PF config** (adopts live prod via
  `import{}`; the OIDF federation gate criterion is authored in `access-token-mappings.tf`; flow =
  apply → export configArchive → commit `data.zip`).
- `deploy/lighthouse/` — the OpenID Federation trust anchor (`oidfed/lighthouse`, digest-pinned).
- `deploy/fedhost/` — static federation entity host (pre-signed statements, per-env content).
- Workflows: `deploy-demo.yml` (UI, push-triggered), `deploy-fedhost.yml`, `deploy-lighthouse.yml`
  (push-triggered per path), `deploy-pingfederate.yml` (**manual scaffold** — build-in-CI from the
  private `pf-integration` carve-out; secrets not yet provisioned).
- Branch→env: `sd-jwt-rar-paz` → staging, `main` → production. **Level since the 2026-07-22 promotion**
  (merge `02b5abd`, PR #2): main carries the full SSF transmitter+receiver, attestation issuance, the
  audit-stream event source, and the SD-JWT removal; the prod services (`pf-demo-ui`, `lighthouse-prod`,
  `fedhost-prod`) were redeployed from it. The PF runtime itself is not push-deployed (manual workflow).
  Known skew: staging service `lighthouse` vs prod `lighthouse-prod` (same for fedhost).

## Harness

`run.sh selfverify` (real attestation verifier in-process), `run.sh ssf-selfverify` (mint+verify a CAEP
SET), `run.sh live <base>` (mint a full attestation against a deployed PF), `probe-challenge.sh` and
`probe-ssf.sh` (endpoint contract tests), `ui/` (the deployed demo — browser walk of the attestation/
federation/RAR flow via a thin Python proxy), `agent-workload/` (deployable SPIFFE-attested agent that
performs the token exchange against PF).

## Docs index

| Doc | Covers |
|---|---|
| [BUILD-AND-DEPLOY.md](BUILD-AND-DEPLOY.md) | build → package → config-as-code → Railway; "what makes a green token" |
| [automatic-registration.md](automatic-registration.md) | OIDF §12.1 transparent registration at the token endpoint |
| [deploy-pingfederate-railway.md](deploy-pingfederate-railway.md) | reproduce the live PF on Railway (TCP-proxy gotcha) |
| [attestation-issuance.md](attestation-issuance.md) | the hosted attester — SPIFFE JWT-SVID → minted client attestation |
| [ssf-transmitter.md](ssf-transmitter.md) | the SSF/CAEP/RISC transmitter + receiver end to end (public demo: `idp-pingfed-ssf-servelet`) |
| [REPO-MAP.md](REPO-MAP.md) | this document |
| [RELATED-REPOS.md](RELATED-REPOS.md) | the ecosystem / overlap map |

## Build notes

- Root artifact: `pf-oidf-modules-0.0.1-SNAPSHOT.jar` (Java 17; `provided` PF SDK 13.0.0.3 +
  servlet-api; bundled jose4j 0.9.6; Jackson deliberately pinned core 2.13.2 / databind 2.19.2 — the
  split makes some non-SSF tests fail **offline only**; CI aligns them).
- Practical build: `mvn -o package -Dassembly.skipAssembly=true -Dpmd.skip=true -Dcheckstyle.skip=true`
  (assembly/PMD/Checkstyle reference the missing `build-tools/`).
- SSF test suite: `mvn -o test -Dtest='com.pingidentity.ps.oidf.ssf.*Test,com.pingidentity.ps.oidf.servlet.ssf.*Test'`.
