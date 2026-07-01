# Building the client & configuring PingFederate

How `pf-oidf-modules` (the OAuth 2.0 Attestation-Based Client Authentication
extension) is built, packaged, configured into a live PingFederate, and deployed
to Railway. This is the end-to-end record of what was done and why.

---

## 0. Starting point

`pf-oidf-modules` is a **CFR-decompiled** PingFederate (PF) OpenID Federation
add-on. Non-obvious facts:

- Sources live at repo root `./com/pingidentity/ps/oidf/...`, **not**
  `src/main/java`. A default-layout `mvn` build finds nothing.
- Because it is decompiled, several files did not recompile as-is (see §2.1).

The work spanned five stages: (1) writing the attestation client-auth extension,
(2) building it without corp Artifactory, (3) packaging it for PF, (4) configuring
a live PF instance, (5) deploying to Railway.

---

## 1. The extension code

Implements **OAuth 2.0 Attestation-Based Client Authentication**
(`draft-ietf-oauth-attestation-based-client-auth-09`), both PoP variants:

- `attest_jwt_client_auth` — dedicated PoP JWT
- `attest_jwt_client_auth_dpop` — DPoP combined mode (DPoP `jwk` must equal the
  attestation `cnf`)

**Framework-agnostic core** (`common/`):

| Class | Role |
|---|---|
| `ClientAttestationVerifier` | orchestrator |
| `DpopProofValidator` | RFC 9449 DPoP proofs |
| `Jwks` | RFC 7638 thumbprints / key checks |
| `FederationAttesterKeyResolver` | resolves attester keys via OpenID Federation |
| `AttestationReplayCache` | `jti` replay protection |
| `AttestationChallengeService` | issues/verifies one-time challenges |
| `AttestationSupport` | shared singletons |
| `ClientAttestationConfig` + DTOs | config & result types |
| `JwtCodec` | `verifyAttestationPop` / `verifyAgainstKeys` / `requireType` |

**PF glue**:

- `clientregistration/utils/ClientAttestationUtils` — the runtime hook
- `servlet/attestation/ClientAttestationChallengeServlet` — challenge endpoint
- edited `FederationService` / `FederationConfiguration` / `AttestationMetadataConfig`
  (metadata) and `RegistrationService` (client mapping)

**Integration model.** PF has no native attestation auth type, so attestation RPs
are registered as **public clients** (`ClientAuthenticationType.NONE`) and enforced
at the token endpoint by an **issuance-criteria OGNL expression**:

```
ClientAttestationUtils.validateClientAttestation(#this)
```

The hook receives a context `Map` (`context.HttpRequest`, `context.ClientId`,
`extproperties.*`). Per-client tuning is via `extproperties.attestation_*`; cache
sizing is via challenge-servlet init-params.

---

## 2. Building without Artifactory

The pom wants `pf-protocolengine` / `pingfederate-sdk` at **13.0.0.3** (PF 13.0.3)
from `art01.corp.pingidentity.com`, which is unreachable. Build instead against a
**local PF distro** (e.g. `~/Downloads/pingfederate-13.0.1`):

1. Build an **uber jar** of all 263 `server/default/lib/*.jar`.
2. **Strip `META-INF`** — jar signatures and the embedded annotation-processor
   service file break `javac`.
3. `mvn install-file` it as `pingfederate:pf-protocolengine:13.0.0.3` with a
   **parent-less POM** — otherwise install-file picks up the jar's embedded pom and
   demands a missing `pingfederate-project-aggregator` parent.
4. Restructure to a real source root:
   `mkdir -p src/main/java && cp -R com src/main/java/`.
5. Build:

   ```
   mvn package -Dassembly.skipAssembly=true -Dpmd.skip=true \
       -Dcheckstyle.skip=true -Dmaven.compiler.proc=none
   ```

Result: **39 unit tests pass**, thin **~109K** jar
(`target/pf-oidf-modules-0.0.1-SNAPSHOT.jar`); jose4j/jackson not shaded.

### 2.1 Decompilation artifacts fixed to make it compile

- `common/TrustChainValidator` — parse moved outside `try` (×2)
- `common/HttpTrustControllerGateway` — uninitialised `endpoint`;
  `MalformedClaimException` moved outside `try` (×2)
- `trustanchor/FederationService` — bogus `java.lang.invoke.CallSite` → `Object`
- `servlet/.../OpenIdRegistrationServlet` (×2) and
  `servlet/trustanchor/OpenIdFederationServlet` (×4) — CFR emitted mangled
  try-with-resources that rethrew an undeclared `Throwable`; replaced with plain
  try-with-resources.

---

## 3. Packaging for PingFederate

PF's runtime is **Jetty 12 EE8 (javax namespace)**. Its `ContextProvider`
(`etc/jetty-runtime.xml`) auto-deploys any **WAR** dropped in
`server/default/deploy/` via `@WebServlet` annotation scanning. **Plain jars are
not auto-deployed.**

- Servlets are packaged as **`oidf.war`** (`WEB-INF/lib/<module>.jar` + jose4j +
  jackson, `web.xml` with `metadata-complete=false`). WAR filename → context path,
  so `oidf.war` serves at **`/oidf`** (challenge endpoint
  `/oidf/federation/attestation-challenge`).
- The OGNL hook cannot run from the WAR classloader, so the module classes are
  **also** dropped as loose jars (`pf-oidf-modules.jar` + `jose4j-0.9.6.jar`)
  directly in `deploy/` for the runtime classpath.

> **Known limitation — classloader split.** Because the hook runs from the loose
> jars and the servlet from the WAR, they hold **separate `AttestationSupport`
> singletons**. So a challenge issued by the servlet isn't visible to the hook.
> The full challenge flow needs the module merged into `pf-runtime.war`.

---

## 4. PingFederate configuration (config-as-code)

Configuration is **baked as a data dir** (`server/default/data/`) copied into the
image — no manual console clicks. It provisions:

- the **OAuth AS** + a reference **access-token manager**,
- the **attestation client** `https://rp.example.com` (public client),
- the **`validateClientAttestation` issuance criterion** on the token flow.

### 4.1 Dev-mode mock attester

To issue tokens before a real OpenID Federation trust controller exists,
`bin/run.properties` sets a system property (PF loads `run.properties` as sys
props):

```
oidf.mock.attesters = <path>/conf/oidf-mock-attesters.json
```

`oidf-mock-attesters.json` is an issuer→JWKS map. A branch in
`ClientAttestationUtils` uses a `StaticAttesterKeyResolver` when that file is
present, **bypassing federation**. A fixed mock-attester keypair: public JWK in the
conf file (pre-trusted by PF), private JWK embedded in the demo UI.

`run.properties` also binds console + engine to **`::` (IPv6 dual-stack)** so
Railway's IPv6 private network can reach the runtime.

---

## 5. Railway deployment

Single fully-configured PF service (`pingfederate-runtime`), built from one Docker
context (`pf-single/`):

```dockerfile
FROM pingidentity/pingfederate:13.0.3-latest
USER root
COPY pingfederate.lic        .../conf/pingfederate.lic
COPY oidf.war                .../deploy/oidf.war
COPY pf-oidf-modules.jar     .../deploy/pf-oidf-modules.jar     # OGNL hook on runtime classpath
COPY jose4j-0.9.6.jar        .../deploy/jose4j-0.9.6.jar
COPY data/                   .../data/                          # config-as-code
COPY bin/run.properties      /opt/in/instance/bin/run.properties # binds ::
COPY conf/oidf-mock-attesters.json .../conf/oidf-mock-attesters.json
RUN chmod -R a+rX /opt/in/instance/server/default
USER 9031
```

(The Ping DevOps entrypoint copies `/opt/in` over the runtime instance on boot.)

Topology notes:

- Railway allows **one public TCP port per service**, and PF can't expose both
  console and runtime publicly on one service over IPv6 — so the **runtime is
  public** and the console is reached privately/locally.
- A separate **`pf-demo-ui`** Python service serves the walkthrough and proxies
  `/api/challenge` + `/api/token` to PF. It **must bind `::` dual-stack** or
  Railway's edge returns 502.
- Needs the **Hobby plan** (free plan can't provision ~2 GB).
- **No persistent volume** → console edits are ephemeral; the baked data dir is the
  source of truth.
- License (ID 01049798, ID Partners) **expires 2026-07-13**.

### Live endpoints (Railway project `pingfederate`)

| Service | URL / proxy |
|---|---|
| `pingfederate-runtime` | TCP `reseau.proxy.rlwy.net:38844` (runtime `:9031`) |
| admin console | TCP `hayabusa.proxy.rlwy.net:25784` (`:9999`) |
| `pf-demo-ui` | `https://pf-demo-ui-production.up.railway.app` |

---

## 6. What makes a green token (current state)

A real `access_token` issues when **all** of these hold:

1. PoP `aud` / DPoP `htu` equals the **actual** token URL —
   `/as/token.oauth2` at ROOT, **not** under `/oidf`.
2. The challenge is **omitted** (`OIDF_NO_CHALLENGE=1`) — see the classloader-split
   limitation in §3.
3. The client has a secret — PF forbids public clients with `client_credentials`,
   so the demo UI proxy injects it.

Verified working **locally and on Railway**.

---

## 7. Verification tooling (`harness/`)

| Command | What it does |
|---|---|
| `harness/run.sh selfverify` | runs the real `ClientAttestationVerifier` in-process (PoP + DPoP); resolves jose4j / jackson / slf4j / commons-logging from `~/.m2` + the built module jar |
| `harness/run.sh live <baseUrl>` | mints a real attestation + PoP + DPoP against the deployed challenge endpoint |
| `harness/probe-challenge.sh` | checks the challenge-endpoint contract |
| `harness/ui/` | the interactive demo + component diagram (the `pf-demo-ui` service) |

---

## 8. Open items

1. **Classloader split** — merge the module into `pf-runtime.war` so the challenge
   servlet and the issuance hook share one `AttestationSupport`, enabling the full
   challenge flow (drop the `OIDF_NO_CHALLENGE` bypass).
2. **Real federation trust** — exercise the
   `FederationAttesterKeyResolver` / `TrustChainValidator` path instead of the
   static mock attester, for a non-dev green token.
3. **Clustering** — challenge/replay state is per-node; a clustered PF needs a
   shared store. Response-header challenge delivery (draft §6.2) is not wired (the
   hook has no response); clients use the challenge endpoint (§6.1).
