# The environment, as code

An **environment** (staging or production) is the whole set of services that make the demo work, not
just the UI. Historically only the demo UI was git-managed; every other service was `railway up`'d by
hand from throwaway `/tmp` contexts with its config living only in Railway's console. That drift is
what let a one-line lighthouse variable change turn into an outage with no git source of truth to
revert to. This tree fixes that: **every service is defined here, and config is code.**

This repo is the sole deployer of Railway project `pingfederate` (`e02a8e2f`).

## The two repos

`pf-agentic-identity` is the **capability** — the Java modules, plugins and servlets, plus the PF
image build (`build/pingfederate/`) that packages them. It deploys nothing and owns no Railway.
This repo is the **demo**: it owns the project, the per-service config, the tokens, and the
deployments.

Between 2026-08-15 and 2026-08-21 the deploy trees lived in the capability repo instead. That was the
wrong way round, and the Actions history is unambiguous about it: here, `deploy-fedhost` and
`deploy-lighthouse` ran green on 2026-07-22 and `deploy-demo` on 2026-08-17; there, with no Railway
tokens, the workflows died at the first CLI call and `deploy-pingfederate` was never dispatched once.
The move took a working pipeline and made it theatre, and left two repos able to deploy the same
services. They are back.

## The pattern

```
deploy/<service>/
  Dockerfile           # or build context — pinned by digest where it's an upstream image
  railway.json         # builder + deploy policy
  vars.staging.env     # non-secret config as code (KEY=value)
  vars.production.env
```

CI is `.github/workflows/deploy-<service>.yml`, path-filtered to `deploy/<service>/**`, applying
`vars.<env>.env` via `railway variables --set` and then `railway up`. **Push to `main` → staging;
production only by explicit `workflow_dispatch`** — no branch deploys production. Tokens are the repo
secrets `RAILWAY_TOKEN_STAGING` / `RAILWAY_TOKEN_PROD`, one Railway *project* token per environment.

**Secrets never live in git.** Master keys, licenses, vault tokens and DB credentials are Railway or
GitHub secrets; `vars.*.env` holds only non-secret config.

## Service inventory — project `e02a8e2f`

| Service | Purpose | Deployed from |
|---|---|---|
| `lighthouse` / `lighthouse-prod` | trust anchor / resolver (go-oidfed, pinned by digest) | [`lighthouse/`](lighthouse) + `deploy-lighthouse.yml` |
| `fedhost` / `fedhost-prod` | serves entity configurations (public JWTs); per-env content via `FEDHOST_CONTENT` | [`fedhost/`](fedhost) + `deploy-fedhost.yml` |
| `pingfederate-runtime` | the AS — PF 13.0.3 + the capability repo's modules | [`pingfederate/`](pingfederate) + `deploy-pingfederate.yml`. **Gated**: the config archive is compromised; see that README |
| `pf-demo-ui` | the attestation demo page | [`../harness/ui/`](../harness/ui) + `deploy-demo.yml` |
| `agent-workload` | SPIFFE-attested demo agent | [`../harness/agent-workload/`](../harness/agent-workload) — **migrating to `idp-agentic-demo`**, whose banking trust domain it already defaults to |
| `railway-workload` | workload-identity demo | **source not located** — find it before touching the service, or retire it |
| `Redis` / `Redis-3siA` | challenge/replay store (`OIDF_REDIS_URL`) | managed Railway resource, no deploy dir |
| `openbao` / `openbao-prod` | secrets vault | dormant. Nothing references its transit key, its public key is trusted nowhere, traffic is zero, and `openbao-prod` has never had a serving deployment. **Deletion recommended.** |
| `webhook-console` | — | no deployment, ever |

Persistent state — the lighthouse volume (anchor key + subordinate DB) and the Redis instances — are
pre-existing Railway resources, created once per environment and never rebuilt from git. PF itself is
ephemeral: its config is the archive baked into the image, which is why an undeployed config change
survives only until the next restart.

## Known cleanups

- **Service-name skew:** staging is `lighthouse`, production is `lighthouse-prod` (the CLI could not
  add a same-named service to a second environment). CI carries per-env names; unify by renaming so
  both environments use one name.
- **Image pinning:** the lighthouse is pinned by digest deliberately — an unpinned `:latest` caused a
  past outage.
- **No health checks** on `fedhost` or `lighthouse`; PF has a 600 s timeout with no path, so it is a
  no-op on the slowest-booting service.
