# Explainers

Self-contained HTML explainers for the attestation-issuance work. Each is a single standalone file —
inline CSS, no external assets, no build step. Open directly in a browser, or host anywhere static.
Both are theme-aware (follow the OS light/dark setting via `prefers-color-scheme`).

| File | What it is | Published copy |
| --- | --- | --- |
| [hosted-attester-explainer.html](hosted-attester-explainer.html) | Marketing / concept explainer for the hosted attester — the code-to-bearer-token lifecycle, the two trust layers (pluggable instance formats: SPIFFE SVID or wallet WIA; three client sources), an on-the-wire request → enriched response, and the standards used. | [claude.ai artifact](https://claude.ai/code/artifact/59f75e1c-5cb4-4245-989d-63cfe3a46d22) |
| [multi-cloud-attestation-architecture.html](multi-cloud-attestation-architecture.html) | Enterprise architecture — SPIFFE/SPIRE per cloud (AWS/Azure/GCP), PingFederate running inside each cloud as the attestation endpoint, verifying `spiffe_id ↔ client_id` registration against the client's OpenID Federation entity record before issuing the client attestation. | [claude.ai artifact](https://claude.ai/code/artifact/ef9d0298-7e12-4086-8eb8-371ef3f5783d) |

## Notes

- These files were authored as claude.ai Artifacts (which wrap a page fragment at publish time). The copies
  here are wrapped into full standalone documents (`<!doctype html>` + `<head>` with the CSS + `<body>`), so
  they render on their own with no host.
- The published artifact URLs are private to their owner until shared from the artifact's Share menu; the
  copies in this repo are the portable, version-controlled source.
- Design docs for the underlying feature live one level up: [../pluggable-instance-attestation.md](../pluggable-instance-attestation.md),
  [../federation-backed-issuance.md](../federation-backed-issuance.md), [../attestation-issuance.md](../attestation-issuance.md).
