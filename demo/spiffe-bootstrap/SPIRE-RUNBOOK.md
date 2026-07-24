# Real SPIRE ↔ OIDF attestation — deploy runbook

Goal: run **real SPIRE** (server + agent) so a workload gets a JWT-SVID over the Workload API, the OAuth
client lib binds that SVID into the OIDF Client Attestation, and the AS (PingFederate) verifies the SVID
against the SPIRE server's bundle. Trust domain: `banking.demo`. SPIRE **1.11.2**, official binaries.

Status: **the flow is proven end-to-end locally** (below). The Railway deployment is the remaining work —
this runbook has the exact steps + the traps already hit.

Contexts here: `spire-server/` (Alpine + `spire-server` + `server.conf` + bundle-JWKS endpoint) and
`spire-agent/` (Alpine + `spire-agent` + a workload that fetches its JWT-SVID). Custom Alpine images because
the official images are distroless (no shell → can't run the CLI or serve the bundle).

## 1. Local proof (works today)

```bash
# server
docker build -t spire-server:demo spire-server
docker network create spire-net
docker run -d --name spiresrv --network spire-net spire-server:demo
docker exec spiresrv /opt/spire/bin/spire-server healthcheck                     # Server is healthy.
TOKEN=$(docker exec spiresrv /opt/spire/bin/spire-server token generate \
          -spiffeID spiffe://banking.demo/agent/payment-node | sed -n 's/^Token: //p')
docker exec spiresrv /opt/spire/bin/spire-server entry create \
   -parentID spiffe://banking.demo/agent/payment-node \
   -spiffeID spiffe://banking.demo/payment-agent -selector unix:uid:0

# agent + workload
docker build -t spire-agent-wl:demo spire-agent
docker run -d --name wl --network spire-net \
   -e SPIRE_SERVER_ADDRESS=spiresrv -e SPIRE_JOIN_TOKEN="$TOKEN" spire-agent-wl:demo
docker logs wl        # → token(spiffe://banking.demo/payment-agent): eyJ…  + the verifying bundle
```

Observed: agent join-token-attests → node SVID; workload attested (`unix:uid:0`) → **JWT-SVID for
`spiffe://banking.demo/payment-agent`** (aud `https://as.banking.demo`), plus the trust bundle to verify it.

## 2. Railway deploy (the remaining work)

> **Trap 1 — always pass `-p`.** `railway up` from a dir named `server` created a *stray project* named
> `server`. Deploy with the explicit project + service every time:
> `railway up --detach -p <pingfederate-project-id> -s spire-server -e staging`.

1. **Create + deploy `spire-server`** into `pingfederate/staging`:
   ```bash
   railway environment staging
   railway add -s spire-server
   ( cd spire-server && railway up --detach -p <PROJECT_ID> -s spire-server -e staging )
   ```
2. **Attach a volume** (persists the CA + registration entries). `railway volume add` has **no `-s`** — it
   operates on the *linked* service, so link first:
   ```bash
   railway service   # link spire-server
   railway volume add -m /opt/spire/data
   ```
   > **Trap 2 — the first deploy FAILED with no runtime logs.** Almost certainly Railway port/healthcheck
   > detection: the container listens on 8081 (gRPC) + 8080 (bundle httpd), not Railway's `$PORT`.
   > First debug step: `railway logs -s spire-server` after boot; fix = bind the bundle httpd to `$PORT`
   > (or set the service's target port), or disable the HTTP healthcheck for this internal-only service.
3. **Bootstrap** (via `railway ssh` / exec into spire-server — the image has a shell):
   `spire-server token generate -spiffeID spiffe://banking.demo/agent/payment-node` and
   `spire-server entry create -parentID … -spiffeID spiffe://banking.demo/payment-agent -selector unix:uid:0`.
   Set the token as `SPIRE_JOIN_TOKEN` on the agent service.
4. **Deploy the workload** (`spire-agent/`) as a service with a **volume** (persists the node SVID so
   restarts re-attest without a new join token):
   `SPIRE_SERVER_ADDRESS=spire-server.railway.internal`, `SPIRE_JOIN_TOKEN=<token>`. Agent → server over the
   private network.
5. **Client bind**: the workload fetches its JWT-SVID (Workload API socket) and folds it into the attestation
   via `client_attestation_sdk.spiffe.to_workload_claim(svid)` (see `../python/examples/spiffe_bridge.py`).
   For a real Workload API client use py-spiffe / go-spiffe, or `spire-agent api fetch jwt`.
6. **AS verification**: PingFederate (the `pf-oidf-modules` plugin) verifies the presented SVID against the
   SPIRE bundle at `http://spire-server.railway.internal:8080/jwks.json` (JWT authorities).

## Gotchas recap
- Always `railway up -p <project>` (stray-project trap).
- `railway volume add` uses the linked service (no `-s`).
- The distroless official images have no shell — build the Alpine wrappers here.
- Join tokens are single-use → give both server and agent volumes so identities persist across restarts.
- Bundle for verification is published at `:8080/jwks.json` by the server's entrypoint.
