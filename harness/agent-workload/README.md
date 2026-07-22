# agent-workload — a real SPIFFE-attested agent

A deployable agent workload for the demo. On boot it attests itself via the SPIFFE agent
(`client_attestation_sdk.spiffe.SpiffeAgent`, the SPIRE Workload API analogue) and obtains a
JWT-SVID. Remotely invoke it to run the token exchange: it builds a Client Attestation whose
`workload` claim carries the SVID identity (`to_workload_claim`), signs it, and presents it to
PingFederate — returning a real Bearer token.

## Endpoints
- `GET /identity` — its SPIFFE ID, JWT-SVID, and attested attributes.
- `POST /invoke` — run the SPIFFE-attested token exchange; returns `{svid, attestation, pop, pf_status, pf_body}`.

## Config (env)
- `PF_TOKEN_ENDPOINT` — PingFederate token endpoint (default: prod runtime).
- `PF_TOKEN_AUD` — the PoP `aud` PF validates against (default `https://localhost:9031`; the public
  railway URL is rejected — the aud/TCP-proxy trap).
- `CLIENT_ID` / `CLIENT_SECRET` — the demo client credentials.
- `AGENT_TYPE` (default `payment-agent`) · `SPIFFE_TRUST_DOMAIN` (default `banking.demo`).

The attestation is signed by the demo mock attester (`kid=mock-attester-1`, pre-trusted in PF's
`oidf-mock-attesters.json`); its `kid` must be `mock-attester-1` to match that JWKS.

## client_attestation_sdk/  — vendored at deploy, NOT committed
This service depends on `client_attestation_sdk` (including the SPIFFE bridge `spiffe.py`), which
lives in the separate polyglot SDK repo (`client-attestation-sdk-polyglot`; the SPIFFE bridge is on
its `spiffe-workload-bridge` branch). It is **gitignored here** to avoid duplicating/publishing that
source. Vendor it before building:

```sh
cp -r <sdk-repo>/python/src/client_attestation_sdk ./client_attestation_sdk
```

The Dockerfile then `COPY`s it into the image. Deployed on Railway as the `agent-workload` service.
