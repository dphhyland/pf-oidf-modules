# pf-oidf-modules

> **📦 Module code AND service deploys have moved:** the canonical home of the modules once developed
> here (federation, client-attestation authenticator + issuer, SSF, RAR→PingAuthorize, GM API) is the
> [**pf-agentic-identity** monorepo](https://github.com/dphhyland/pf-agentic-identity) — one `mvn package`
> builds everything — and since 2026-08-15 so is every Railway **service deploy** (`pingfederate-runtime`,
> `lighthouse`, `fedhost` + the PF Terraform), verified end to end on staging from the monorepo's own
> build (both demo flows below issue a token against it). **This repo is now the demo + harness home
> only**: no Java, no Maven, no PingFederate module source. See [deploy/README.md](deploy/README.md) and
> [docs/RELATED-REPOS.md](docs/RELATED-REPOS.md) for how the two repos relate.

PingFederate · Attestation-Based Client Authentication demo — a browser page that drives a **live**
PingFederate instance through a local proxy, showing OpenID Federation 1.0 automatic registration
(§12.1) and OAuth 2.0 Attestation-Based Client Authentication
([draft-ietf-oauth-attestation-based-client-auth](https://www.ietf.org/archive/id/draft-ietf-oauth-attestation-based-client-auth-10.html))
end to end: mint keys in the browser, get a one-time challenge, build the attestation + PoP/DPoP proof,
call the token endpoint, and watch PingFederate resolve the client through the trust controller.

## Contents

| Path | What it is |
|---|---|
| [`harness/ui/`](harness/ui) | The demo — a stdlib-only Python server (`server.py`) + `index.html`, deployed as `pf-demo-ui`. Points at whatever PingFederate you give it via `PF_BASE` / `TOKEN_ENDPOINT`; does its own crypto in the browser with WebCrypto. See [deploy/README.md](deploy/README.md) for its service vars. |
| [`harness/probe-challenge.sh`](harness/probe-challenge.sh), [`probe-ssf.sh`](harness/probe-ssf.sh) | Shell contract tests against a **deployed** PingFederate's attestation-challenge and SSF endpoints — `curl` only, no build step. |
| [`harness/*.java`](harness/README.md) | In-process verification harnesses that compile against the module's own classes. **Currently broken** — see below. |
| [`demo/spiffe-bootstrap/`](demo/spiffe-bootstrap) | A local SPIRE server/agent + workload rig for the SPIFFE-attestation demo path. |
| [`docs/`](docs) | Design docs and runbooks — [`REPO-MAP.md`](docs/REPO-MAP.md) inventories what's here, [`RELATED-REPOS.md`](docs/RELATED-REPOS.md) maps the ecosystem. |
| [`.github/workflows/deploy-demo.yml`](.github/workflows/deploy-demo.yml) | Push `main` → deploys `pf-demo-ui` to **staging**; production is a deliberate `workflow_dispatch` choice. |

## ⚠️ `harness/*.java` needs a decision

`AttestationFlowHarness.java`, `AttestationIssuanceHarness.java`, and `SsfSelfVerify.java` compile
against the module classes that lived in `com/` — now deleted, since that source is superseded in
pf-agentic-identity (confirmed file-by-file, 2026-08-15; see the Phase 3 triage in
[docs/RELATED-REPOS.md](docs/RELATED-REPOS.md)). `harness/run.sh selfverify` cannot work any more: there
is no `pom.xml`, no `target/pf-oidf-modules-*.jar` to build. Not yet resolved which of these three ways
to go:

1. **Move them into pf-agentic-identity**, beside the code they exercise — the cleanest end state; this
   repo would then have no Java at all.
2. **Repoint them** at pf-agentic-identity's installed jars — keeps a Maven build here, re-creates a
   version-skew surface (harness built against jar X, deployed PF running Y).
3. **Delete them** — only if the monorepo's own unit tests are judged to already cover what they proved
   end-to-end (in-process minting/verifying against the real classes, not mocks).

`harness/probe-challenge.sh` / `probe-ssf.sh` are unaffected — pure HTTP, no build.

## License

Apache-2.0 — see [LICENSE](LICENSE). PingFederate is a Ping Identity product and is not included in
this repository.
