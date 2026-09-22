# The conformance rig

A throwaway PingFederate, built from [pf-agentic-identity](https://github.com/dphhyland/pf-agentic-identity)'s
image, that exists to be pointed at the [OpenID Foundation conformance suite](https://www.certification.openid.net/).
It is configured to pass two test plans and nothing else:

| Plan | Variant |
|---|---|
| `fapi2-security-profile-final-test-plan` | `private_key_jwt`, DPoP, `plain_fapi`, OpenID Connect |
| `openid-ssf-transmitter-test-plan` | discovery metadata, dynamic auth (`private_key_jwt` client credentials), poll and push |

CIBA is not here yet. The suite's only CIBA plan is `fapi-ciba-id1-test-plan`, which profiles CIBA over
FAPI **1** Advanced - certificate-bound tokens and all - so it is a second rig configuration rather than
a third plan on this one.

It runs the **whole module set**, because that is what is being tested: PingFederate with the modules
merged into `pf-runtime.war`, not PingFederate with them taken out. Federation and attestation are
present, inert and closed - see `vars.env` for what that takes, which is not what you would guess.

Railway project `pf-conformance` (`3c876ea8`), one service, `pingfederate`. Delete the project when
the certification runs are done; nothing else depends on it.

## Why it is shaped like this

**A TCP proxy, not an HTTP domain.** The suite opens its own TLS handshakes against the token,
authorization and userinfo endpoints and fails anything it does not like about the listener, so the
listener has to be PingFederate's and not Railway's edge. A TLS-passthrough proxy to 9031 gives it
that, keeps `https` in the URL PingFederate sees (DPoP's `htu` is compared with it), and will carry a
client certificate when CIBA needs one. The suite does not validate the server certificate, so
PingFederate's self-signed one is fine.

**The issuer is the proxy's host and port**, `terraform/variables.tf` `pf_base_url`. The suite's first
check is that the issuer in discovery is byte-for-byte the URL it fetched discovery from. The proxy's
address is not known until the proxy exists, so: create the service and the proxy, read the address,
then author.

**Authored locally, shipped as an archive.** The rig's admin API is not exposed and is not going to
be. `terraform/` is applied to a stock PingFederate on this machine; the realised config is exported
as `data.zip`; the image imports it at boot. The rig has no volume, so that archive *is* its
configuration - a change made any other way is gone at the next restart.

**The cipher list is in two places.** `config-store/com.pingidentity.crypto.SunJCEManager.xml` takes
the CBC and static-ECDH suites out, because the suite offers them and fails a server that accepts.
It is laid over the image *and* carried in the archive; that file's header says why either alone
looks fine and is not.

**Two FAPI 2.0 rules are enforced by a filter, not by PingFederate.** 13.0.3 accepts a client
assertion addressed to its token endpoint (or the PAR endpoint, or an array) where the profile says
issuer-only, as a string; and it accepts an RS256-signed DPoP proof where the profile says PS256, ES256
or EdDSA. Neither can be configured away on 13.0 - the first has a switch in 13.1
(`Rfc7523bisCompliantAudienceVerification`), the second has none in either. pf-agentic-identity's
`Fapi2ProfileFilter` enforces both for the clients `OIDF_FAPI2_CLIENTS` names, which here is the two
FAPI clients and deliberately not `*`: the suite's SSF client is an ordinary OAuth client that
addresses its assertion to the token endpoint, and with the rules applied to everyone the SSF plan
went from 19 of 19 to 1 of 19.

**Staying on 13.0.3 is not a choice this rig made.** PingFederate 13.1 moved to `jakarta.servlet`,
and the modules are compiled against `javax.servlet`: on a 13.1.3 base image the merged
`pf-runtime.war` fails to start at all. Until the modules are migrated, 13.0.x is the ceiling.

## What the suite says, and what it does not

Against the deployed rig, driven by a suite run locally at release-v5.3.1 (and, before that, the same
results against a local copy of the image):

- `openid-ssf-transmitter-test-plan`, poll delivery: 19 of 19 PASSED.
- `fapi2-security-profile-final-test-plan`: of 56 modules, 49 PASSED, 4 REVIEW, 2 WARNING, 1 SKIPPED,
  none FAILED (2026-09-21). Expect, and do not be alarmed by:
  - **WARNING** on discovery - PingFederate publishes vendor metadata the suite does not know.
  - **WARNING** on authorization-code reuse - PingFederate refuses the second use, as required, but
    does not also revoke the token the first use produced, which the profile only says it should.
  - **REVIEW** on four `request_uri` modules - PingFederate answers a reused, expired or foreign
    `request_uri` with an HTTP 400 error *page* ("invalid_request_uri"), not a redirect. The suite
    accepts a picture of the page and asks a person to look at it. One of the four is a
    recommendation 13.0.3 does not follow: it spends a `request_uri` when the authorization page is
    loaded rather than when the user authorizes (FAPI 2.0 §5.3.2.2 NOTE 3).
  - **SKIPPED** on the claims-parameter module - not supported, not advertised, so not tested.

None of that is a certification. A run that counts is made on the hosted suite, by a person who is
signed in. The hosted suite was at release-v5.3.1 on 2026-09-23 - the same release as the local run -
so the results should carry over; what a hosted run adds is the suite's own network path to the rig.
SSF push delivery has not been run at all: it needs a suite PingFederate can call back, which a suite
on localhost is not.

Railway's TCP proxy passes PingFederate's TLS alerts through untouched - TLS 1.0 and 1.1 are refused
with `protocol_version`, which is one of the signals the suite accepts - and the DevOps licence this
rig fetched at boot reports an expiry of 2/10/27, so it will not lapse mid-certification.

## Doing it

```sh
./gen-keys.sh                      # the suite clients' key pairs + two generated secrets (all git-ignored)

( umask 177; printf 'PING_IDENTITY_PASSWORD=%s\n' "$(openssl rand -base64 30 | tr -d '/+=')Aa1" > ~/.pf-conformance-author.env )
export PF_AUTHOR_ENV=~/.pf-conformance-author.env
./author.sh                        # a stock PF 13.0.3 on https://localhost:19999, cipher list staged
./apply.sh apply                   # terraform -> that server
./export.sh                        # -> data.zip + overlay/   (refuses an archive that would fail the suite)

# the image: pf-agentic-identity's build, this rig's archive. A worktree is fine - that is how a
# branch of the capability repo gets tested here before it merges.
( cd ../../../pf-agentic-identity && mvn -q -DskipTests package && build/pingfederate/stage-modules.sh )
CTX="$(PF_AGENTIC_IDENTITY_HOME=../../../pf-agentic-identity ./compose-context.sh)"

./set-secrets.sh                   # a PERSON runs this: DevOps licence credentials + one generated secret
railway up "$CTX" --path-as-root --service pingfederate --no-gitignore --ci
```

The non-secret service variables are `vars.env`. Change the issuer in one place and you have changed
it in three: `pf_base_url`, `OIDF_SSF_ISSUER`, and the two federation variables.

## Testing it

`suite/render.sh` turns `suite/*.template.json` into the configurations a suite wants, with the
clients' private keys and the test user's password filled in. The rendered files are git-ignored.

**Against a suite you run yourself** (dev mode, no login), `suite/run-plan.py` creates the plan, runs
every module and prints each failure's condition and message:

```sh
suite/run-plan.py https://localhost:9643 openid-ssf-transmitter-test-plan suite/ssf-transmitter.json \
  --variant ssf_server_metadata=discovery ssf_delivery_mode=poll ssf_auth_mode=dynamic ssf_profile=default \
            server_metadata=discovery client_registration=static_client client_auth_type=private_key_jwt

suite/run-plan.py https://localhost:9643 fapi2-security-profile-final-test-plan suite/fapi2.json \
  --variant client_auth_type=private_key_jwt sender_constrain=dpop fapi_profile=plain_fapi openid=openid_connect
```

A local suite can also test a local copy of the rig *as* the rig, without deploying anything: publish
the image on the proxy's port (`-p 127.0.0.1:50715:9031`) and give the suite's `server` container
`extra_hosts: ["thomas.proxy.rlwy.net:host-gateway"]`. The issuer then matches, the TLS probes reach
the real listener, and the only thing that cannot be tested is SSF push, where PingFederate would have
to call the suite back on a loopback name and the servlet's outbound policy rightly refuses to.

**Against the hosted suite**, for a run that counts: sign in at certification.openid.net, create the
plan with the same variants, and paste the rendered configuration. The redirect URIs for both suites
are already registered (`suite_base_urls`).

The suite drives PingFederate's login and consent pages itself. The `browser` block's selectors are
the ids in PingFederate's stock `html.form.login.template.html` and
`oauth.approval.page.template.html`; restyle those pages and the block needs to follow.

## What is not in git, and must not be

`keys/`, `secrets.env`, `data*.zip`, `overlay/`, `suite/*.json`, terraform state. The archive is
shipped in plaintext, which the image build warns about loudly and correctly; `export.sh` says why
that is tolerable for this rig and for nothing whose master key protects anything real.
