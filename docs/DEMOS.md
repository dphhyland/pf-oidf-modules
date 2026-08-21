# Demos — the OpenID Federation + client-attestation demos

This repo owns Railway project `pingfederate` (`e02a8e2f`) and the demos that run on it. The modules
these demos exercise are built in
[`pf-agentic-identity`](https://github.com/dphhyland/pf-agentic-identity) — the capability repo, which
deploys nothing — and consumed through a **sibling checkout**, so clone them under one parent:

```
Source/
  pf-agentic-identity/    # the capability: modules + the PF image build
  pf-oidf-modules/        # this repo: the demos and project e02a8e2f
```

Moved here 2026-08-21 from that repo's `docs/DEMOS.md`, which described demos it no longer owns.
Its other sections went to `idp-agentic-demo` (RAR→PingAuthorize, Grant Evaluation) and
`pf-agentic-identity-domain-authority` (the cross-platform rigs, device enrolment).

Where a demo below is broken, author-local or torn down, it says so; nothing is papered over.
State as recorded 2026-08-15 unless noted.

---

## 1. Staging environment

**What it shows.** The platform running: `lighthouse` (go-oidfed trust anchor / resolver),
`fedhost` (static federation entity host — the `as-emea` / `as-partner` / `as-external` /
`as-unknown` entities the demo UI resolves against), and `pingfederate-runtime` (PF 13.0.3 with the
reactor's modular jars merged into `pf-runtime.war` at root context). Railway project
`e02a8e2f-ff38-4043-836f-25d9e1c0f26b`, environment `staging`.

| Service | Host | Defined by |
|---|---|---|
| lighthouse | `https://lighthouse-staging-e017.up.railway.app` | `deploy/lighthouse/` |
| fedhost | `https://fedhost-staging.up.railway.app` (entities under `/e/<name>/.well-known/openid-federation`) | `deploy/fedhost/` |
| pingfederate-runtime | `https://pingfederate-runtime-staging.up.railway.app` (Railway HTTPS edge → PF's HTTP listener 9080); admin console/API only via the service's TCP proxy — address and credentials in the Railway service variables, not here | `deploy/pingfederate/` |

**How a change deploys.** Push to `main` touching `deploy/lighthouse/**` or `deploy/fedhost/**`
runs the matching workflow's staging job (applies `vars.staging.env`, `railway up`). Production is a
`workflow_dispatch` with `environment=production`. PF is `workflow_dispatch`-only and gated on the
per-env `data.<env>.zip` archives (see [deploy/README.md](../deploy/README.md)); today it is deployed
by hand from a staged context:

```sh
# from the repo root — needs overlay/pf.jwk + overlay/pingfederate-system-keys.xml (secret) and
# data.zip (the terraform Phase-2 export) already in deploy/pingfederate/, all git-ignored
mvn -q -DskipTests package && deploy/pingfederate/build/stage-modules.sh
( cd deploy/pingfederate && railway up --detach --no-gitignore \
    -p e02a8e2f-ff38-4043-836f-25d9e1c0f26b -s pingfederate-runtime -e staging )
```

`--no-gitignore` is what `deploy-pingfederate.yml` passes — without it `railway up` drops the
git-ignored `modules/`, `data.zip` and `overlay/` from the upload and the Docker build fails on its
`COPY`s (`deploy/pingfederate/README.md` shows the command without the flag; `.railwayignore`
governs what is excluded once the flag is on).

**The mock-attester DEV mode.** `deploy/pingfederate/oidf-mock-attesters.json` is baked into the
image and activated by `oidf.mock.attesters=…` in `run.properties`. It maps attester issuers
(`urn:agent:northwind-*`, the demo's `https://attester.example.com` with `kid=mock-attester-1`) to
public JWKs, so an attestation signed by one of those keys is trusted **statically** — no federation
chain resolution for the attester. It is how the demo UI, the agent workload and the cross-platform
rigs mint attestations without standing up a real Client Attester; production trust goes through
the trust chain (`FederationAttesterKeyResolver`) instead. Adding a new demo attester means adding
its public JWK there and redeploying.

**PF config is Terraform.** `deploy/pingfederate/terraform/` — author `.tf` → `terraform apply`
against the running PF → `helpers/export-data-zip.sh` → commit `data.<env>.zip` → redeploy. The
runbook is [its README](../deploy/pingfederate/terraform/README.md); `TF_VAR_environment` picks the
environment and the PF base URL (`server-settings.tf`).

**Verify:**

```sh
curl -s https://lighthouse-staging-e017.up.railway.app/.well-known/openid-federation | cut -d. -f2 | tr '_-' '/+' | jq -R '@base64d | fromjson | .iss'
curl -s https://pingfederate-runtime-staging.up.railway.app/.well-known/openid-configuration | jq -r .issuer
curl -s https://pingfederate-runtime-staging.up.railway.app/.well-known/openid-federation | cut -d. -f2 | tr '_-' '/+' | jq -R '@base64d | fromjson | .iss'
```

**Known at time of writing:** the staging PF still advertises the old EKS rig's ELB hostname as its
OAuth issuer (the first Phase-2 archive was exported from that rig) — the `server-settings.tf`
per-environment base URL exists to fix exactly this and is being applied; until it lands, clients
must send that ELB as the `private_key_jwt` `aud` (`PF_TOKEN_AUD` on the demo UI).

---

---

## 2. Demo UI / harness

**What it shows.** [pf-oidf-modules](https://github.com/dphhyland/pf-oidf-modules) `harness/ui/` — a
browser UI (stdlib Python proxy + WebCrypto in the page; no private key touches the server) that
drives a live PF through the whole attestation client-auth flow: keys → challenge → Client
Attestation + PoP/DPoP → token endpoint, plus federation resolution against lighthouse/fedhost,
§12.1/§12.2 registration, the hosted-attester minting tab, and remote invocation of a SPIFFE-attested
`agent-workload`. Deployed as `pf-demo-ui`: `https://pf-demo-ui-staging.up.railway.app` /
`https://pf-demo-ui-production.up.railway.app` (both live).

**Deploys from that repo**, not this one — `deploy-demo.yml`, path-filtered to `harness/ui/**`, with
its **own** mapping: push to `sd-jwt-rar-paz` → staging, push to `main` → production. Do not read
that mapping across to this repo (here `main` is staging).

**Run it locally** against staging (Python 3, no pip installs):

```sh
cd ../pf-oidf-modules
PF_BASE=https://pingfederate-runtime-staging.up.railway.app python3 harness/ui/server.py
# open http://localhost:8800
```

`PF_BASE` is the PF **root** — the modules are merged into `pf-runtime.war`, so there is no `/oidf`
prefix (the script's default `https://localhost:19031/oidf` is the old loose-war local layout).
`LIGHTHOUSE` / `FEDHOST` default to the staging hosts; `PF_TOKEN_AUD` must equal what PF advertises
as issuer + `/as/token.oauth2` (see the note in §1); the two demo clients (`demo-attest-inline`,
`demo-attest-vault`) come from `deploy/pingfederate/terraform/attestation-demo-clients.tf` here.

**Wire-level probes** in `harness/` still work against any deployment (from `../pf-oidf-modules`;
verified against staging today):

```sh
harness/probe-challenge.sh https://pingfederate-runtime-staging.up.railway.app   # challenge-endpoint contract test
```

**Broken, honestly:** the in-process Java harnesses (`harness/run.sh selfverify | issuance-selfverify
| ssf-selfverify`) compile `harness/*.java` against a single module jar and import
`com.pingidentity.ps.oidf.common.*` — a package this repo unwound on 2026-08-15 (`.jose` /
`.clientattestation` / `.federation` / `.pf` / `.issuer`). They will not compile against current
monorepo artifacts until the harness sources are translated; that repo's own `com/` tree is the
non-canonical pre-monorepo copy. `harness/agent-workload/` (the SPIFFE-attested Python workload,
Railway service `agent-workload`) is unaffected — it vendors `client_attestation_sdk` from
`client-attestation-sdk-polyglot`, gitignored, before build.

---

---

## 5. SSF transmitter/receiver demo

**What it shows.** [idp-pingfed-ssf-servelet](https://github.com/dphhyland/idp-pingfed-ssf-servelet):
a docker-compose stack of PF 13.0.3 with the SSF module merged into `pf-runtime.war`, the ID Partners
**Identity Object Model** store (Postgres 16, `idm.entry` JSONB; `storeDialect=ldm`) as the
transmitter's persistence, and a single-page demo UI behind a credential-injecting proxy. An 11-stage
probe: transmitter metadata → `ssf.manage` bearer → stream CRUD → SCIM-driven subjects → verification
SET polled/acked → PF logout emits `caep.session-revoked` (`LogoutEventFilter` over
`/idp/init_logout.openid`) → SCIM disable emits `risc.account-disabled` → state survives a PF restart →
**loopback push (RFC 8935)**: PF's transmitter delivers to PF's own receiver, which verifies the SET and
runs the grant-revocation action. Public deployment (Railway project `ssf-demo`, live): UI
`https://ssf-demo-ui-production.up.railway.app`, transmitter
`https://pingfederate-ssf-production.up.railway.app/.well-known/ssf-configuration`.

**Bring it up** (from that repo's README; Ping DevOps credentials for licensing):

```sh
cd ../idp-pingfed-ssf-servelet
cp .env.example .env         # PING_IDENTITY_DEVOPS_USER / _KEY — never commit
# pf/*.jar is gitignored — see the note below on where the jar must come from
docker compose up -d --build
./scripts/bootstrap-pf.sh    # one-time: licence agreement, admin, ATM, mapping, ssf.manage scope, receiver client
./scripts/probe-demo.sh      # the 11-stage walk
# UI http://localhost:18080 · admin https://localhost:19999/pingfederate/app (administrator / 2FederateM0re)
# store: docker compose exec ldm-store psql -U ldm -d ldm
```

**Where the module jar must come from — an open backport.** That README says to build
`pf-oidf-modules.jar` in the pf-oidf-modules repo and copy it to `pf/`; its `pf/Dockerfile` `COPY`s a
single `pf-oidf-modules.jar` and its own (older, single-jar) `assemble-pf-runtime-war.sh`. **The
canonical SSF source is this repo** (`servlets/ssf`, plus `oidf-jose` / `pf-integration` it depends
on) — pf-oidf-modules is backport-only. The demo has not been repointed: this repo's
`deploy/pingfederate/build/assemble-pf-runtime-war.sh` already accepts a directory of jars (the
`modules/` shape from `stage-modules.sh`), so the fix is to `COPY modules/` there and call the
directory form — tracked as a drift-rule-2 backport, not yet done. Until then the compose builds from
whatever `pf/pf-oidf-modules.jar` is lying around (the local copy is dated 2026-07-21, pre-unwind).

---
