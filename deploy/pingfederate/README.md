# `pingfederate-runtime` — the deployment

The Railway deployment of the OIDF/attestation AS (project `2a226db6`, services
`pingfederate-runtime` in both environments). **This directory does not contain the image build.**

| Half | Where it lives | Why |
|---|---|---|
| The image build — `Dockerfile`, `assemble-pf-runtime-war.sh`, `stage-modules.sh`, the config-store overlay | [`pf-agentic-identity`](https://github.com/dphhyland/pf-agentic-identity) `build/pingfederate/` | three repos consume it, and `pf-agentic-identity-domain-authority` pushes the image to **ECR** — it is not a Railway artefact |
| The deployment — `railway.json`, `vars.<env>.env`, `.railwayignore`, `terraform/`, the archive and its keys, the demo attester trust | here | this repo owns project `2a226db6` and holds the tokens |

`railway up` needs both in one directory, so [`compose-context.sh`](compose-context.sh) joins them into
`.context/` (git-ignored, entirely derived — delete it freely).

## Deploying by hand

```sh
# once: clone the capability repo beside this one, or set PF_AGENTIC_IDENTITY_HOME
#   Source/pf-agentic-identity/
#   Source/pf-oidf-modules/          <- you are here

./deploy/pingfederate/compose-context.sh      # builds + stages the modules if needed
( cd deploy/pingfederate/.context && railway up --detach --no-gitignore \
    -p 2a226db6-e288-4194-9e91-f56491290761 -s pingfederate-runtime -e staging )
```

`--no-gitignore` is not optional: `.context/` is git-ignored in full, and without the flag
`railway up` uploads nothing and the Docker build fails on its first `COPY`.

## What you must stage here first (all git-ignored)

| Path | What it is |
|---|---|
| `data.zip` | the configArchive for the environment you are deploying. **A configArchive is a plain zip that contains `pf.jwk`** — the master key that decrypts every secret in it. It is a secret; never commit one. `terraform/helpers/export-data-zip.sh` writes the per-env `data.<env>.zip`. |
| `overlay/pf.jwk`, `overlay/pingfederate-system-keys.xml` | the key and system keys matching that archive |

> **The current key is compromised.** `data.staging.zip` was committed to a public remote under the
> belief that "encrypted with `pf.jwk`" made it safe. Kid `GsG6aqYBaO` is treated as dead, CI refuses
> to deploy rather than re-bake it, and the replacement is an `age`-encrypted archive decrypted at
> boot from a sealed Railway variable — so the key sits in neither git nor an image layer.

## The trust anchor's keys are pinned, not fetched

Since 2026-09-15 the capability refuses to validate any OpenID Federation trust chain unless it has the
anchor's public keys in `OIDF_FEDERATION_TRUST_ANCHOR_JWKS` (OpenID Federation 1.0 §4 distributes them
out of band). Before that it read them from the anchor's `/.well-known/openid-federation` on every
walk, so whoever answered HTTPS at the anchor's hostname was the anchor.

| Environment | Anchor | Pinned |
|---|---|---|
| staging | `trust-controller-staging` | yes, in `vars.staging.env` — kid `icyaNxdj6OHG9lSMPMQm6CuXP1Wf2e4aGT09V_qjDS8`, captured over HTTPS; still to be confirmed against `/data/keys` on its volume |
| production | `trust-controller-prod` | **no** — it returned 404 when this was done, so production PF validates no chain until it is set |

Capture or refresh with the capability repo's tool, then compare the printed thumbprint with the key the
anchor holds before deploying:

```sh
../pf-agentic-identity/tools/pin-trust-anchor.py https://trust-controller-staging.up.railway.app \
  --env-file deploy/pingfederate/vars.staging.env
```

A missing value does not stop PF booting: this war also serves PF's own federation endpoints, so the
filters log an error at startup and refuse per request instead. A value that is set but malformed does
stop it. On an anchor key rollover, pin the new key alongside the old and deploy *before* the anchor
starts signing with it.

## CI

[`deploy-pingfederate.yml`](../../.github/workflows/deploy-pingfederate.yml) — `workflow_dispatch`
only, per-environment, gated on the archive above (it fails deliberately, before writing any key
material, rather than deploying from a committed one). It checks out `pf-agentic-identity`, builds the
reactor there, stages the modules, composes the context and `railway up`s it.

Because the modules are merged into `pf-runtime.war` at the **root** context, their endpoints have no
`/oidf` prefix — the challenge endpoint is `/federation/attestation-challenge` and
`/.well-known/ssf-configuration` is at root. Repoint any `/oidf/*` consumer (the demo UI's `PF_BASE`)
accordingly.

## The archive carries whatever the AS learned

A PF configArchive is a snapshot of `server/default/data`, and OpenID Federation §12.1 automatic
registration writes real OAuth clients into exactly that directory. So an archive exported from a
running demo carries every client the demo auto-registered while it ran, keyed by the entity id the
browser minted at the time — and those entity keys were thrown away when the tab closed.

That is harmless until an entity id repeats. On the 2026-09-22 rebuild the demo UI came back at
`pf-demo-ui-staging.up.railway.app` (the previous environment had a suffixed domain), which made the
browser mint `…/e/payment-agent` — the exact client_id already baked into `data.zip` from an earlier
session. Auto-registration then had nothing to do, and PF authenticated the assertion natively
against the *baked* JWKS, whose private half no longer exists anywhere. The demo failed with
`HTTP 400` and a `ClientPrivateKeyJwtValidator` "unable to find a suitable verification key", which
reads like a broken trust chain and is nothing of the sort — the chain resolved fine.

**Before shipping an archive, drop the auto-registered clients.** They are the ones whose `client_id`
is an `https://…/e/<slug>` URL rather than a name you chose:

```sh
unzip -q data.zip -d dz && grep -rl 'client_id="https://.*/e/' dz/oauth-clients/
```

Delete those files, re-zip, redeploy. The deliberate demo clients (`https://rp.example.com`,
`demo-attest-*`, `urn:agent:*`, `northwind-*`) all stay.
