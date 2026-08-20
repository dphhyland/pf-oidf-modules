# `pingfederate-runtime` — the deployment

The Railway deployment of the OIDF/attestation AS (project `e02a8e2f`, services
`pingfederate-runtime` in both environments). **This directory does not contain the image build.**

| Half | Where it lives | Why |
|---|---|---|
| The image build — `Dockerfile`, `assemble-pf-runtime-war.sh`, `stage-modules.sh`, the config-store overlay | [`pf-agentic-identity`](https://github.com/dphhyland/pf-agentic-identity) `build/pingfederate/` | three repos consume it, and `pf-agentic-identity-domain-authority` pushes the image to **ECR** — it is not a Railway artefact |
| The deployment — `railway.json`, `vars.<env>.env`, `.railwayignore`, `terraform/`, the archive and its keys, the demo attester trust | here | this repo owns project `e02a8e2f` and holds the tokens |

`railway up` needs both in one directory, so [`compose-context.sh`](compose-context.sh) joins them into
`.context/` (git-ignored, entirely derived — delete it freely).

## Deploying by hand

```sh
# once: clone the capability repo beside this one, or set PF_AGENTIC_IDENTITY_HOME
#   Source/pf-agentic-identity/
#   Source/pf-oidf-modules/          <- you are here

./deploy/pingfederate/compose-context.sh      # builds + stages the modules if needed
( cd deploy/pingfederate/.context && railway up --detach --no-gitignore \
    -p e02a8e2f-ff38-4043-836f-25d9e1c0f26b -s pingfederate-runtime -e staging )
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

## CI

[`deploy-pingfederate.yml`](../../.github/workflows/deploy-pingfederate.yml) — `workflow_dispatch`
only, per-environment, gated on the archive above (it fails deliberately, before writing any key
material, rather than deploying from a committed one). It checks out `pf-agentic-identity`, builds the
reactor there, stages the modules, composes the context and `railway up`s it.

Because the modules are merged into `pf-runtime.war` at the **root** context, their endpoints have no
`/oidf` prefix — the challenge endpoint is `/federation/attestation-challenge` and
`/.well-known/ssf-configuration` is at root. Repoint any `/oidf/*` consumer (the demo UI's `PF_BASE`)
accordingly.
