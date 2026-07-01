# Attestation client-auth demo UI

A browser UI that drives a **live** PingFederate instance to show the attestation
client-authentication flow end to end — the same flow the `harness/` scripts run,
but visualized step by step.

## Run

```bash
python3 harness/ui/server.py            # defaults to the Railway runtime
# then open http://localhost:8800
```

Point it at a different PingFederate runtime:

```bash
PF_BASE=https://<host>:<port>/oidf python3 harness/ui/server.py
# token endpoint defaults to https://<host>:<port>/as/token.oauth2 (override with TOKEN_ENDPOINT)
```

No pip installs — Python 3 standard library only.

## Why the local server?

The browser can't call PingFederate directly: it's behind a Railway TCP proxy with a
self-signed cert (CN=`localhost`) on a non-standard port, and the challenge servlet sends
no CORS headers. `server.py` serves the page and forwards two calls to PF
(`/api/challenge`, `/api/token`), accepting the self-signed cert. All key generation and
JWT signing happens **in your browser** via WebCrypto — no private keys touch the server.

## What it shows

1. **Generate keys** — EC P-256 Client Attester + client instance keys (WebCrypto).
2. **Fetch a challenge** — `POST /oidf/federation/attestation-challenge`; shows the
   one-time value, `Cache-Control: no-store`, and HTTP status.
3. **Build artifacts** — the Client Attestation JWT (`cnf`-bound) plus a PoP JWT or DPoP
   proof echoing the challenge; headers/payloads decoded. Toggle PoP / DPoP at the top.
4. **Call the token endpoint** — posts the `OAuth-Client-Attestation` (+ `-PoP`/`DPoP`)
   headers and shows PingFederate's response, with the equivalent `curl`.

Step 4 returns a token only once the OAuth AS + a public client (`attestation_required=true`)
+ the `validateClientAttestation` issuance criterion are configured in PingFederate; until
then it shows PF's error response (proof the request reached the AS). Steps 1–3 — the
servlet itself — work against the stock deployment today.
