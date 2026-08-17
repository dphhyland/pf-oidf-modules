# Service deploys moved — this repo deploys the demo UI only

The environment-as-code tree that used to live here (`deploy/{fedhost,lighthouse,pingfederate}` and
their `deploy-fedhost.yml` / `deploy-lighthouse.yml` / `deploy-pingfederate.yml` workflows) now lives
in the **[pf-agentic-identity](https://github.com/dphhyland/pf-agentic-identity) monorepo**, under its
own `deploy/`. That is where every Railway service in project `pingfederate` (`e02a8e2f`) is defined
and deployed from.

**Why it moved.** Both repos carried deployable definitions for the *same* Railway services, already
diverged, so either repo could silently undo the other's deploy. The module code that PingFederate
actually runs is built in the monorepo (one `mvn package` → the modular jars staged by
`deploy/pingfederate/build/stage-modules.sh`), so the deploy definition belongs beside it. Verified
end to end on staging 2026-08-15: the PF runtime built from the monorepo's jars serves both demo
flows — attestation-based client auth and OpenID Federation §12.1 automatic registration.

## What this repo still deploys

| Service | Context | Workflow |
|---|---|---|
| `pf-demo-ui` | [`harness/ui/`](../harness/ui) | [`deploy-demo.yml`](../.github/workflows/deploy-demo.yml) — push-triggered: `sd-jwt-rar-paz`→staging, `main`→production |

Everything else — `pingfederate-runtime`, `lighthouse`/`lighthouse-prod`, `fedhost`/`fedhost-prod`,
`Redis`, `openbao` — is deployed from **pf-agentic-identity**, including the PF Terraform
config-as-code (`deploy/pingfederate/terraform/`, the source of truth for `data.<env>.zip`).

## Demo UI configuration (Railway service vars)

The UI is a thin proxy; it points at whatever PF you give it. Since the module is merged into
`pf-runtime.war` at the **root** context, there is no `/oidf` prefix:

| Var | Staging value | Note |
|---|---|---|
| `PF_BASE` | `https://pingfederate-runtime-staging.up.railway.app` | root context — no `/oidf` |
| `TOKEN_ENDPOINT` | `<PF_BASE>/as/token.oauth2` | |
| `PF_TOKEN_AUD` | PF's **configured base URL** | PF core accepts only its own base URL as `aud`; behind Railway's TLS-terminating edge that is an `http://` value. Check `pingfederate_server_settings` in the monorepo's terraform if tokens fail with "doesn't contain an acceptable identifier". |

## Known cleanups (carried over)

- **Service-name skew:** staging is `lighthouse`, production is `lighthouse-prod` (the CLI couldn't add
  a same-named service to a second env). The monorepo's CI carries per-env names; unify by renaming so
  both envs use one service name.
- **Image pinning:** the lighthouse is pinned by digest on purpose — an unpinned `:latest` is what
  caused a past outage.
