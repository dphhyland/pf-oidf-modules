# Stand up PingFederate (OIDF attestation module) on Railway — runbook

Reproduces the live staging instance: **PingFederate 13.0.3 + the `pf-oidf-modules` attestation module +
a pre-trusted mock attester**, deployed to Railway as a Docker image, exposed via a **TCP proxy**, with the
**demo UI** pointed at it. Written for a fresh session on **this machine** (`/Users/davidhyland/Source/pf-oidf-modules`).

Companion memory: `railway-deploy-and-build.md`, `staging-mirror-and-sdjwt-live.md` (same project — read them for the *why*).

---

## 0. Prerequisites

- **Railway CLI**, authenticated (`railway whoami`). PF needs ~2 GB RAM → a **Hobby-plan** workspace.
  Prefix Railway CLI calls with `RAILWAY_CALLER=skill:use-railway@1.3.3 RAILWAY_AGENT_SESSION=<stable-id>`.
- A **PingFederate license** (`pingfederate.lic`). The one in the reusable context (ID Partners) **expires 2026-07-13** — check validity; get a fresh one if expired.
- The base image `pingidentity/pingfederate:13.0.3-alpine_3.23.4-al21-latest` (pulled automatically by the build).

---

## 1. Fast path — reuse the prebuilt build context

This session left a **complete, ready-to-deploy Docker context** at **`/tmp/pf-staging/ctx`**:

```
Dockerfile  data.zip  overlay/  pingfederate.lic
oidf.war  pf-oidf-modules.jar  jose4j-0.9.6.jar  oidf-mock-attesters.json
```

`data.zip` is a real PF config archive (demo OAuth client `https://rp.example.com`, the `attestATM` access-token
manager, and PF's master key `pf.jwk`). The module jars carry the attestation + SD-JWT verifier. If that dir still
exists, you can deploy straight from it (Step 3). If it's gone, rebuild it (Step 2).

> ⚠️ That `data.zip` + `pf.jwk` are **prod-derived** (they embed prod's master key and admin creds). Fine for a
> throwaway clone; do not treat the resulting instance as isolated from prod's secrets.

---

## 2. Rebuild the artifacts from scratch (only if `/tmp/pf-staging/ctx` is gone)

**2a. Build the module jar** (offline; the PF SDK is already installed in `~/.m2` as
`pingfederate:pf-protocolengine:13.0.0.3`). From the repo root:

```bash
# the build tree is src/main/java (gitignored); the tracked copy is com/. Keep them in sync:
mkdir -p src/main/java && cp -R com/* src/main/java/com/   # only if src/main/java is missing
mvn -o -q package -Dassembly.skipAssembly=true -Dpmd.skip=true -Dcheckstyle.skip=true -Dmaven.compiler.proc=none
# → target/pf-oidf-modules-0.0.1-SNAPSHOT.jar
```

If the SDK is NOT in `~/.m2`: build an uber jar of `~/Downloads/pingfederate-13.0.1/pf-install/pingfederate/server/default/lib/*.jar`,
**strip `META-INF`** (signatures + the annotation-processor service file break `javac`), and `mvn install:install-file`
it as `pingfederate:pf-protocolengine:13.0.0.3` with a **parent-less** pom. (See `railway-deploy-and-build.md`.)
Jackson is pinned to **2.17.1** in the pom because `~/.m2` lacks newer Jackson core.

**2b. Assemble the WAR.** `oidf.war` = a zip with `WEB-INF/lib/{pf-oidf-modules-…jar, jose4j-0.9.6.jar}` +
`WEB-INF/web.xml` (`metadata-complete="false"` so Jetty runs `@WebServlet` annotation scanning). WAR filename →
context path, so `oidf.war` serves at **`/oidf`**. Easiest: copy the existing `oidf.war` and swap the module jar:

```bash
cp /tmp/pf-staging/ctx/oidf.war ./oidf.war
mkdir -p _w/WEB-INF/lib && cp target/pf-oidf-modules-0.0.1-SNAPSHOT.jar _w/WEB-INF/lib/pf-oidf-modules-0.0.1-SNAPSHOT.jar
( cd _w && zip -q ../oidf.war WEB-INF/lib/pf-oidf-modules-0.0.1-SNAPSHOT.jar ) && rm -rf _w
```

**2c. Config archive (`data.zip`).** A flat zip of a PF `server/default/data` directory (config-only). It must contain
the demo OAuth client + its ATM + `pf.jwk`. Reuse `/tmp/pf-staging/ctx/data.zip`, or build one from a running PF
(`railway ssh … tar czf - -C /opt/out/instance/server/default data`, then trim runtime dirs — see
`staging-mirror-and-sdjwt-live.md` for the exact `zip -x` excludes + the `ForceUnsupportedImport` overlay).

**2d. Mock attester + license.** `oidf-mock-attesters.json` (public JWK the verifier pre-trusts) and
`pingfederate.lic` — copy both from `/tmp/pf-staging/ctx`.

---

## 3. The Dockerfile

Already in `/tmp/pf-staging/ctx/Dockerfile`:

```dockerfile
FROM pingidentity/pingfederate:13.0.3-alpine_3.23.4-al21-latest
USER root
# config archive → drop-in-deployer (imported at boot)
COPY --chown=9031:0 data.zip /opt/in/instance/server/default/data/drop-in-deployer/data.zip
# overlay the archive's master key + system keys BEFORE the deployer runs, so encrypted secrets decrypt
COPY --chown=9031:0 overlay/pf.jwk /opt/in/instance/server/default/data/pf.jwk
COPY --chown=9031:0 overlay/pingfederate-system-keys.xml /opt/in/instance/server/default/data/pingfederate-system-keys.xml
# force import: a raw-zipped archive has no version stamp (safe: same 13.0.3 image)
COPY --chown=9031:0 overlay/config-store/org.sourceid.saml20.domain.mgmt.impl.DataDeployer.xml /opt/in/instance/server/default/data/config-store/org.sourceid.saml20.domain.mgmt.impl.DataDeployer.xml
COPY --chown=9031:0 pingfederate.lic /opt/in/instance/server/default/conf/pingfederate.lic
COPY --chown=9031:0 oidf.war            /opt/in/instance/server/default/deploy/oidf.war
COPY --chown=9031:0 pf-oidf-modules.jar /opt/in/instance/server/default/deploy/pf-oidf-modules.jar   # loose jar = the OGNL hook
COPY --chown=9031:0 jose4j-0.9.6.jar    /opt/in/instance/server/default/deploy/jose4j-0.9.6.jar
COPY --chown=9031:0 oidf-mock-attesters.json /opt/in/instance/server/default/conf/oidf-mock-attesters.json
RUN sed -i 's/^pf.http.port=-1/pf.http.port=9080/' /opt/staging/instance/bin/run.properties.subst.default
# DEV: trust the mock attester (else the hook tries real federation resolution and rejects it)
RUN printf '\noidf.mock.attesters=/opt/out/instance/server/default/conf/oidf-mock-attesters.json\n' >> /opt/staging/instance/bin/run.properties.subst.default
USER 9031:0
```

> The `oidf.attestation.required.claims=workload` line from staging is **optional** (AS-side required-claims
> enforcement) — leave it out for a plain instance. (Note: its live activation is currently unreliable — see Gotchas.)

---

## 4. Deploy to Railway

```bash
ENVPREFIX='RAILWAY_CALLER=skill:use-railway@1.3.3 RAILWAY_AGENT_SESSION=pf-standup-1'

cd /tmp/pf-staging/ctx
# new project + service from this Docker context (railway detects the Dockerfile):
env $ENVPREFIX railway init --name my-pingfederate      # or target an existing project/env with -p/-e
env $ENVPREFIX railway up --detach                      # builds the image + deploys
# watch: railway logs -n 200   → wait for Jetty to start the /oidf context + "PingFederate ... started"
```

PF boot takes **2–4 min** after the build (drop-in-deployer import + Jetty). `/opt/out` is an **ephemeral overlay**
(no volume), so the archive re-imports on every deploy.

---

## 5. Expose it — use a TCP proxy, NOT the app domain (critical)

The attestation **PoP `aud` must equal PF's request-derived token-endpoint URL.** Railway's app domain
(`*.up.railway.app`) **terminates TLS** → PF sees `http://…` → the demo's `https://…` `aud` **mismatches** → the
token request fails `attestation_validation_failed`. A **TCP proxy passes TLS through** → PF terminates it → sees
`https://…` → `aud` matches. PF already listens HTTPS on 9031.

```bash
env $ENVPREFIX railway tcp-proxy create --port 9031 -s <service> -e <env>
# → e.g. hayabusa.proxy.rlwy.net:21695   (PF's self-signed cert; the demo proxy accepts it)
```

Endpoints then are:
- challenge: `https://<host>:<port>/oidf/federation/attestation-challenge`
- token:     `https://<host>:<port>/as/token.oauth2`

---

## 6. Wire the demo UI (optional but recommended)

The demo is a separate Railway service built from **`harness/ui/`** (`Dockerfile`, `python:3.12-slim`, `server.py`
binds `::` dual-stack). It's a thin proxy: the browser calls `/api/challenge` + `/api/token`; `server.py` forwards
to PF and injects the demo client's id+secret. Configure via env vars:

```bash
cd /Users/davidhyland/Source/pf-oidf-modules/harness/ui
env $ENVPREFIX railway up --detach -s <demo-service>
env $ENVPREFIX railway variables -s <demo-service> \
  --set "PF_BASE=https://<tcp-host>:<tcp-port>/oidf" \
  --set "TOKEN_ENDPOINT=https://<tcp-host>:<tcp-port>/as/token.oauth2" \
  --set "CLIENT_ID=https://rp.example.com" \
  --set "CLIENT_SECRET=<demo client secret>"        # must match the client baked in data.zip
```

`server.py` uses an unverified SSL context, so PF's self-signed cert on the TCP proxy is fine.

---

## 7. Verify

```bash
# challenge endpoint (proves the module WAR + mock attester loaded):
curl -sk -X POST "https://<tcp-host>:<tcp-port>/oidf/federation/attestation-challenge"
# → {"attestation_challenge":"…","expires_in":300}

# full token flow: open the demo, walk steps 1–4 (Plain JWT + SD-JWT toggles) → HTTP 200, token issued.
```

PF log confirms success:
`ClientAttestationUtils … Attestation-based client authentication succeeded … mode=POP_JWT … granted_authorization_details=1`

---

## 8. Gotchas (hard-won)

- **TCP proxy, not app domain** — the `aud`/scheme trap above. #1 cause of `attestation_validation_failed`.
- **Mock attester** — needs `oidf.mock.attesters` in `run.properties` (baked by the Dockerfile) pointing at
  `conf/oidf-mock-attesters.json`; else the hook does real federation resolution and rejects the demo attester.
- **Client must have a secret** — PF forbids public clients with `client_credentials`; the demo client is a
  `SECRET` client and the demo proxy supplies the secret. Real client auth is the attestation hook on top.
- **`/oidf/.well-known/openid-federation` returns 500** — the federation-metadata servlet needs a `trustAnchorIssuers`
  init-param that the WAR's empty `web.xml` doesn't provide. **This does NOT affect the token/attestation flow** (it's a
  separate servlet); the challenge servlet + token endpoint work regardless. Ignore it.
- **Drop-in-deployer version stamp** — a hand-`zip`-ped archive has no version marker → the deployer halts unless
  `ForceUnsupportedImport=true` (the `DataDeployer.xml` overlay). Safe when both sides are the same 13.0.3 image.
- **Master key** — if the archive's secrets were encrypted under a different `pf.jwk`, overlay that `pf.jwk`
  (Dockerfile does) so they decrypt; otherwise client-auth fails `invalid_client`.
- **Ephemeral `/opt/out`** — no persistent volume; console-made changes are lost on redeploy. Config is code (the archive).
- **License expiry** — the ID Partners license expires **2026-07-13**.

---

## 9. Known issue — AS-side required-claims enforcement

The optional `oidf.attestation.required.claims` feature (reject a presentation that withholds a required claim) is
**implemented + unit-tested** but its **live activation on Railway is currently unreliable** — the deployed jar + JVM
system property are both confirmed correct, yet the OGNL issuance-criterion hook appears to execute an older module
class (a PF classloader/OGNL-compile caching effect with the loose-jar deployment). Suspected fix: **merge the module
into `pf-runtime.war`** (single classloader) instead of a loose `deploy/*.jar`. See `staging-mirror-and-sdjwt-live.md`.
Leave the property out unless you're working that issue.
```
