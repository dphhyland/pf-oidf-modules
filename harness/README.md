# Harness

## In-process verification CLIs — moved (2026-08-18)

`AttestationFlowHarness.java`, `AttestationIssuanceHarness.java`, `SsfSelfVerify.java`, and `run.sh`
moved to **[pf-agentic-identity](https://github.com/dphhyland/pf-agentic-identity)**'s
`services/harness/` — see [its README](https://github.com/dphhyland/pf-agentic-identity/blob/main/services/harness/README.md)
to build and run them. They compiled against the module classes that lived in `com/` here, which no
longer exist in this repo (superseded, deleted 2026-08-18 — see the top-level README).

## What's still here

| File | What it does | Needs |
|------|--------------|-------|
| `probe-challenge.sh` | Contract test of a **live** challenge endpoint (status, `no-store`, JSON shape, uniqueness) | `curl` |
| `probe-ssf.sh` | Contract test of a **live** SSF endpoint | `curl` |
| `ui/` | The demo — `pf-demo-ui`. See [../deploy/README.md](../deploy/README.md). |
| `agent-workload/` | SPIFFE-attested demo workload (SDK vendored, gitignored) |

```bash
harness/probe-challenge.sh
harness/probe-challenge.sh https://<host>/federation/attestation-challenge
harness/probe-ssf.sh
```

## Deployment context path

The attestation module is merged into `pf-runtime.war` and served at the **server root** — `PF_BASE`
carries no `/oidf` prefix. The challenge endpoint is `https://<host>/federation/attestation-challenge`.
