# SPIFFE → Client Attestation bootstrap

A workload proves **what it is** with a SPIFFE **JWT-SVID**, and PingFederate's issuance servlet turns
that infra identity into a **Client Attestation** the workload can authenticate an OAuth client with.
Infra identity in; a signed, key-bound client credential out — no pre-shared secret ever issued to the
instance.

```
  ┌─────────────┐  join-token   ┌────────────┐   Workload API    ┌──────────────┐
  │ spire-server │◀────attest────│ spire-agent │◀─── JWT-SVID ─────│  workload    │
  │  + bundle    │               └────────────┘                   │ (payment-    │
  │  JWKS :8080  │                                                │  agent)      │
  └──────┬───────┘                                                └──────┬───────┘
         │ bundle (JWKS)                                                 │ ① SVID
         ▼                                                               ▼
  ┌───────────────────────────────────────────────────────┐   ② POST /federation/attestation
  │ issuer = AttestationIssuanceServlet (in PingFederate)  │◀──────  {svid, instance_key, proof}
  │  · validate SVID against the registered SPIFFE bundle  │
  │  · mint a Client Attestation bound to the instance key │──────▶  minted Client Attestation
  └───────────────────────────────────────────────────────┘   ③ POST /as/token.oauth2
                                                                  (attestation + PoP) → access token
```

Two tiers, one bootstrap: SPIFFE says *what/where the workload is*; the minted Client Attestation says
*this instance of a registered client may get tokens, bound to its own key*.

## §Verified — run it now, no Docker

The bootstrap **logic** and the **workload client** are proven end-to-end over real HTTP against the
**real `AttestationIssuanceServlet`** — no SPIRE or PingFederate containers required:

```bash
./run-local.sh            # harness serves the servlet AND drives a workload through it, in one process
./run-local.sh workload   # harness serves; the REAL Python workload (workload/bootstrap.py) bootstraps against it
```

Both print the JWT-SVID → minted Client Attestation → issued token and exit 0. This is the exact wire
contract the compose and PingFederate paths serve; `harness/BootstrapHttpHarness.java` is the reference.

## §Compose — real SPIRE + the servlet + the workload

```bash
issuer/stage.sh              # stage the servlet jar + its deps (incl. your ~/.m2 PingFederate SDK jar)
docker compose up --build
```

- **spire-server / spire-bootstrap / spire-agent** — real SPIRE 1.11.2 (Alpine-wrapped; the official
  images are distroless). The bootstrap one-shot creates the registration entry + a join token; the agent
  attests and exposes the Workload API socket. (This half is proven locally in `SPIRE-RUNBOOK.md`.)
- **issuer** — the real `AttestationIssuanceServlet` (via the harness serve mode), configured with
  `SPIRE_BUNDLE_URL` so it validates SVIDs against the **actual** spire-server bundle.
- **workload** — `workload/bootstrap.py`, fetching its SVID from the agent (`spire-agent api fetch jwt`),
  then bootstrapping an attestation and getting a token.

> **Honesty:** the SPIRE↔agent↔workload container choreography (join-token handoff, socket volumes) is
> assembled here but **not run in-sandbox** (no Docker daemon was available). `docker compose config`
> validates and every script lints; expect to shake out the SPIRE join dance on first `up` — the
> `SPIRE-RUNBOOK.md` traps apply.

## §Deploy the servlet into PingFederate (the faithful "in PF" path)

The `issuer` container hosts the *same* servlet class a PingFederate deployment runs. To use real PF:

1. **Package the servlet into the war.** `AttestationIssuanceServlet` currently ships in its own module and
   is **not yet bundled in any `oidf.war`** — add `attestation-issuer.jar` (+ its issuance `common` classes)
   to the war's `WEB-INF/lib`, or add the module as a dependency of the war assembly.
2. **Register the client + its issuance config** as OAuth-client **extended properties** (the servlet reads
   them via its `IssuanceClientResolver`):
   ```
   attestation_issuer          = https://attester.banking.demo
   attestation_trust_domain    = banking.demo
   attestation_spiffe_bundle   = <spire-server bundle JWKS, or a URL to it>
   attestation_signing_jwk     = <the attester private JWK>        (or attestation_signing_key_ref for OpenBao)
   attestation_instances       = [{"spiffe_id":"spiffe://banking.demo/payment-agent",
                                    "entitlement":[{"type":"sales_agent","sales_regions":["EMEA"]}],
                                    "metadata":{"region":"EMEA"}}]
   ```
3. **License PF** via the estate's DevOps mechanism (`PING_IDENTITY_DEVOPS_USER/KEY` + `ACCEPT_EULA=YES`);
   point `workload`'s `PF_BASE` at PF and mind the **`aud`/TCP-proxy trap** (the PoP `aud` must match PF's
   configured base URL, not the external host).

## Status

| Piece | State |
|---|---|
| Bootstrap logic (SVID→mint→verify) over HTTP | ✅ verified (`run-local.sh`), servlet unit tests green |
| Python workload client | ✅ verified against the serving harness (`run-local.sh workload`) |
| SPIRE server + agent contexts | ✅ proven locally (`SPIRE-RUNBOOK.md`); wired into compose here |
| docker-compose wiring | 🟡 assembled + `config`-valid; not run in-sandbox (no Docker) |
| Servlet packaged into `oidf.war` | ❌ packaging gap — see §Deploy step 1 |
| Live PingFederate demo | ❌ follow §Deploy (issuer container hosts the same servlet meanwhile) |
