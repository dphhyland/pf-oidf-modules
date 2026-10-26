# The rig's agent types on PingFederate

The observatory ([dphhyland/idp-observatory](https://github.com/dphhyland/idp-observatory), plan
`private/plans/agent-identity.md`, phase 1) runs its coding agents through **agentd** in two modes. On
PingFederate each mode is an **agent type**, an OpenID Federation entity this authority hosts and vouches
for; each running agentd process is an **instance** that authenticates at the token endpoint with a Client
Attestation bound to its own key plus a DPoP proof (`attest_jwt_client_auth_dpop`, combined mode). The
**observatory is the Client Attestation Service** for those instances: it mints the attestation (the Mac
runner's key approved by an admin, a Railway replica's key corroborated against its deployment), and
PingFederate trusts it as the `oauth_client_attester` its Entity Configuration declares, resolved through
the trust chain. PingFederate's own attester (`/federation/attestation`) is not in the path: it validates
cloud, SPIFFE and wallet evidence only, which a Mac or a Railway replica has none of.

This directory is the config as code for that, and the runbook. The story is pf-oidf-modules#6.

| File | What it is |
|---|---|
| `agentd-docker.json`, `agentd-railway.json` | Each type's `oauth_client` metadata: `client_credentials`, scope `runner`, `token_endpoint_auth_method` `attest_jwt_client_auth_dpop`, the attester named. |
| `mint-type.mjs` | Mints a type as a **self-signed** hosted entity: its Federation Entity Key, the `POST /federation/agents` body and the signed Entity Configuration. Self-signed because this project's OpenBao (the usual `hostingKeyRef`) was never rebuilt ([../../README.md](../../README.md)). |
| `../vars.staging.env` | The non-secret settings below. |

## Settings (`vars.staging.env`)

| Setting | Value | Why |
|---|---|---|
| `OIDF_AUTHORITY_ENTITY_ID` | the runtime's external base URL (the same as `OIDF_SSF_ISSUER`) | Hosting: each type's entity id is `<this>/federation/agents/<id>`, built from the setting, never from `Host`. Unset, PingFederate hosts nothing. |
| `OIDF_RAR_EXTRA_TYPES` | `agent-runner` | The `authorization_details` type a runner's token carries. A type named here needs a model, or it is `UNMODELLED_TYPE` in production. |
| `OIDF_RAR_MODELS` | `{"types":{"agent-runner":{"fields":{"pool":"equal","repos":"set"}}}}` | The model: `pool` compared for equality, `repos` as a set (a request may name a subset of the ceiling). The ceiling is the instance's, carried in its attestation's `authorization_details` by the observatory; the token endpoint holds what it issues to that ceiling (pf-agentic-identity `docs/operator/issued-details.md`), so no per-client ceiling is configured here. |
| `OIDF_SCIM_AGENT_INSTANCES` | `true` | The AgentInstance SCIM resource at `/federation/scim/v2/*`. Note: it lists the registers of PingFederate's own attester (device instances, workload agents), which the rig's observatory-minted instances are not in; until an agent register is configured it answers 503 after the token check. On for the plan's phase 3, which is being re-scoped for that. |
| `OIDF_CLIENT_REVOCATION_CAEP` | `true` | A suspended, revoked or reactivated instance in those registers goes out as a CAEP or RISC event to the SSF streams that hear it. Same caveat. |
| `OIDF_BRIDGE_SIGNING_KEYS` | a file path, set at apply time | `attest_jwt_client_auth` is bridged to `private_key_jwt` inside PingFederate with one key per client (pf-agentic-identity `docs/configuration/federation-runtime.md`; `OIDF_BRIDGE_PRIVATE_JWK` was retired in 0.1.2 and is refused). The file maps each type's entity id to `{"jwk": <private JWK>}`. It is a secret, so it is not in this tree: see the next section. **Until it is set, `OIDF_ATTESTATION_REQUIRE_BRIDGE_KEY=false` stays, which keeps attestation-based client authentication inert.** |

Already in place from earlier work: the trust anchor (`OIDF_FEDERATION_TRUST_ANCHORS`, the pinned
`OIDF_FEDERATION_TRUST_ANCHOR_JWKS`), the trust controller (`OIDF_FEDERATION_TRUST_CONTROLLER_HOST`) and
`OIDF_REQUIRE_METADATA_POLICY=false` (the staging anchor publishes no policy; demo posture).

## Secrets (never in git)

| Secret | Where | What |
|---|---|---|
| `AGENT_TYPE_KEY_AGENTD_DOCKER`, `AGENT_TYPE_KEY_AGENTD_RAILWAY` | Railway variables on the PF service that hosts the types (on pf-rig: project `d1b703dd`, service `pingfederate`; PF does not read them) | Each type's Federation Entity private JWK, as `mint-type.mjs --key-out` wrote it. Needed to re-sign the Entity Configuration, which PingFederate accepts for at most 7 days (604800 s), so weekly, and after every PF restart on an in-memory authority. |
| the bridge keys file | a Railway secret on `pingfederate-runtime`, materialised at the path `OIDF_BRIDGE_SIGNING_KEYS` names | `{"<entity id of agentd-docker>": {"jwk": {...}}, "<entity id of agentd-railway>": {"jwk": {...}}}`, one fresh P-256 private JWK per type. The PingFederate image's entrypoint (pf-agentic-identity `build/pingfederate/pf-entrypoint.sh`) writes no file from a variable today, so this needs either a mounted secret file or a small entrypoint addition in the capability repo; decide at apply time. Rotate with `OIDF_BRIDGE_PREVIOUS_PUBLIC_JWK` during the overlap. |

## Runbook: register the types

Every call below is an operator route: a PingFederate-issued access token with the route's scope,
DPoP-bound in production (`oidf.admin.entities` to create and publish; pf-agentic-identity
`docs/operator/operator-authentication.md`). In development `OIDF_AUTHORITY_ADMIN_TOKEN` works as a bearer.
`$PF` is `OIDF_AUTHORITY_ENTITY_ID`.

```sh
cd deploy/pingfederate/agents
for t in agentd-docker agentd-railway; do
  node mint-type.mjs --authority "$PF" --type $t --key-out $t.key.json          # key-in to re-sign
  curl -sS "$PF/federation/agents" -H "Authorization: DPoP $TOKEN" -H "DPoP: $PROOF" \
       -H 'Content-Type: application/json' --data @$t.registration.json
  curl -sS -X PUT "$PF/federation/agents/$t/entity-configuration" \
       -H 'Content-Type: application/entity-statement+jwt' --data-binary @$t.entity-configuration.jwt
  curl -sS "$PF/federation/agents/$t/.well-known/openid-federation" | cut -d. -f2 | base64 -d | jq .metadata.oauth_client
done
```

The publication needs no token: the signature by a registered key is the authorisation. PingFederate refuses an
Entity Configuration that lives longer than 7 days ("lifetime exceeds 604800 seconds"), so `mint-type.mjs` signs
for 7 at most: re-sign with `--key-in` weekly. Then store each `*.key.json` as its Railway variable and delete the
file. Check the chain from the anchor:
`curl "$ANCHOR/resolve?sub=$PF/federation/agents/agentd-docker&anchor=$ANCHOR"` should return a resolved
statement whose `metadata.oauth_client.token_endpoint_auth_method` is `attest_jwt_client_auth_dpop`.

Registration lasts until the chain expires or `OIDF_REGISTRATION_MAX_TTL_SECONDS` (a day), re-resolved on
the next token request; suspend, reactivate or revoke a type with `/federation/admin/entities/*`
(pf-agentic-identity `docs/federation/operations.md`, "Hosting agents"). With the authority's entities in
memory (no `OIDF_AUTHORITY_DATA_STORE_ID` on staging) a restart forgets them: register again, with
`--key-in` so the keys stay.

## Runbook: the observatory as the attester

PingFederate resolves the attester named by an attestation's `iss` through the trust chain and takes its
keys from the resolved `metadata.oauth_client_attester.jwks` (pf-agentic-identity
`docs/openid-client-attestation-service-1_0.md` section 5.2). So `https://devops.mydigitalid.info` must be a
subordinate of the staging trust anchor: enrol its Federation Entity Keys (from its
`/.well-known/openid-federation`) at the trust controller's admin API the way
[`../../fedhost/remint.mjs`](../../fedhost/remint.mjs) `--register` enrols the fedhost entities, and
confirm `curl "$ANCHOR/resolve?sub=https://devops.mydigitalid.info&anchor=$ANCHOR"` returns its
`oauth_client_attester` metadata. `OIDF_ATTESTATION_REQUIRED_TRUST_MARKS` stays unset: the attester's
evidence (admin-approved key, corroborated deployment) is in the attestation's `workload.attested_by`,
not in a Trust Mark, for now.

## In the configuration archive (admin console or Terraform, then a redeploy)

These live in PingFederate's own configuration, which this project bakes into the image as the archive
([../README.md](../README.md): the archive is gated on the key rotation):

1. **Scope** `runner`, exclusive, so only these clients ask for it.
2. **The access token manager** the auto-registered clients get: JWT, `typ` `at+jwt`, PS256, 10 minutes,
   with `cnf.jkt` (DPoP), audience `https://devops.mydigitalid.info/runner`, and an attribute contract with
   `agent_id` fulfilled by the OGNL expression
   `@com.pingidentity.ps.oidf.servlet.clientregistration.utils.ClientAttestationUtils@attestationClaim(#this,"agent_id")`
   `authorization_details`
   are issued by the RAR plugin and held to the attestation's ceiling.
3. **The challenge endpoint** `POST /federation/attestation-challenge` on (its `DPoP-Nonce` is the
   combined-mode challenge; pf-agentic-identity `docs/configuration/attestation-challenge.md`).
4. **An operator client for the observatory** (phase 3): client credentials, DPoP required, scopes
   `oidf.admin.read`, `oidf.admin.scim.read` and `oidf.admin.scim`, and the `OIDF_OPERATOR_*` settings.

## Verify

```sh
NONCE=$(curl -sS -X POST "$PF/federation/attestation-challenge" | jq -r .attestation_challenge)
curl -sS "$PF/as/token.oauth2" -d grant_type=client_credentials -d scope=runner \
     -H "OAuth-Client-Attestation: $ATTESTATION" -H "DPoP: $DPOP"      # DPoP nonce = $NONCE, jwk = the attestation's cnf.jwk
```

The token decodes to `typ` `at+jwt`, `iss` `$PF`, `aud` `https://devops.mydigitalid.info/runner`,
`cnf.jkt` the instance key's thumbprint, `agent_id` the runner name, and `authorization_details` with
`type` `agent-runner`, `pool` and `repos` within the attestation's.

## Status

**Applied on 2026-10-27 to pf-rig** ([`../../pf-rig/`](../../pf-rig/README.md)): both types registered (201),
published (204) and resolving through PF's own anchor (`/federation/resolve` 200). On pf-rig, `$ANCHOR` is the PF
itself and the operator is `OIDF_AUTHORITY_ADMIN_TOKEN`. Not yet there: `authorization_details` in tokens (no RAR
processor in the image), the bridge keys, SCIM and CAEP (see pf-rig's Known gaps).

Earlier: written on 2026-10-27 while the staging PingFederate, trust controller and fedhost were **not deployed**:
the Railway project the plan names (`2a226db6`) and the one `../../README.md` names (`dfb3922e`) are both
gone, and every `*-staging.up.railway.app` host answers "Application not found". Nothing here has been
applied; the first apply is a restore of the environment from this tree (plus the federation re-mint), then
the runbooks above in order.
