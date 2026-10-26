# pf-rig: the lean PingFederate for the observatory's agent rig

One Railway service, `pingfederate` in project **pf-rig** (`d1b703dd-487d-4439-869e-023a5b0a8da0`), at
`https://pingfederate-production.up.railway.app`. It is the authorization server for the observatory's agent
runners (idp-observatory `private/plans/agent-identity.md`) and, to stay lean, **its own trust anchor**: no
trust controller, fedhost, OpenBao, Redis or Postgres. It stands in for the staging environment in
[`../README.md`](../README.md), which was gone from Railway on 2026-10-27, and runs pf-agentic-identity **0.8.0**
built from source (commit `b8eef30b`; the last GitHub release is 0.7.0).

Lean means **development posture**: `OIDF_DEPLOYMENT_PROFILE=development`, everything in memory (hosted agent
types, challenges, replay caches) and lost at a restart, the plain listener on 9080 behind Railway's TLS edge, and
the static `OIDF_AUTHORITY_ADMIN_TOKEN` as the operator. It is not a production PingFederate.

## What is here

| File | What it is |
|---|---|
| `observatory-rig.yaml` | The deployment profile (pf-agentic-identity `tools/profile.py`): attestation auth on (issuer off: the observatory is the attester), federation with auto-registration, hosting, the operator API and `self_anchor`, the SSF transmitter, scope `runner`, the `agent-runner` RAR type. |
| `overlay.py` | What the profile's Terraform does not do, applied to the authoring PF before export: OGNL expressions on, the JWT access token manager's audience `https://devops.mydigitalid.info/runner`, and `agent_id` in the token from the verified Client Attestation (`ClientAttestationUtils@attestationClaim(#this,"agent_id")`). |
| `compose-context.sh` | The `railway up` context: pf-agentic-identity's `build/pingfederate/` (modules staged) plus `railway.json` and the **encrypted** archive. Refuses a plaintext one. |
| `railway.json` | Dockerfile build, health check `/pf/heartbeat.ping` (PORT 9080), 600 s. |
| `rig-entrypoint.sh` | The boot shim (the image's last stage, appended by `compose-context.sh`): writes `OIDF_BRIDGE_SIGNING_KEYS_JSON` to the file `OIDF_BRIDGE_SIGNING_KEYS` names (0600), then runs the image's own entrypoint. Without bridge keys `ATTESTATION_AUTH` is `FAILED_CONFIG`. |
| `register-types.sh` | Registers, publishes and resolves both agent types from the keys on the service's variables. Run after every restart and weekly. |
| `age-recipient.txt` | The public age recipient the archive is encrypted to. The identity is the `PF_ARCHIVE_AGE_KEY` variable only. |

## Variables on the service

Non-secret: the profile's rendered env (`tools/profile.py author` writes it to `<out>/vars.env`) and `PORT=9080`.
Secret, set through `railway variable set --stdin`, never in git or on disk afterwards; seal them in the dashboard:
`PING_IDENTITY_DEVOPS_USER`, `PING_IDENTITY_DEVOPS_KEY` (the evaluation licence is fetched at boot and lasts about
7 days, so restart the service weekly), `PF_ARCHIVE_AGE_KEY`, `OIDF_AUTHORITY_ADMIN_TOKEN`, `AGENT_TYPE_KEY_AGENTD_DOCKER`,
`AGENT_TYPE_KEY_AGENTD_RAILWAY` (PF doesn't read these; `register-types.sh` does), and `OIDF_BRIDGE_SIGNING_KEYS_JSON`:
`{"<type entity id>": {"jwk": <P-256 private JWK>, "attesters": ["https://devops.mydigitalid.info"]}}` per type, with
`OIDF_BRIDGE_SIGNER_BACKING=config` and `OIDF_BRIDGE_SIGNING_KEYS=/tmp/oidf-bridge/keys.json`. Each entry binds the type to
the observatory as its only attester (`OIDF_ATTESTATION_REQUIRE_ATTESTER_BINDING` defaults to true).

## Rebuild from scratch

```sh
# 1. modules (pf-agentic-identity, JDK 17): mvn -B -DskipTests package; build/pingfederate/stage-modules.sh --profile production
# 2. a self-signed runtime keystore and a secrets file OUTSIDE the repo: runtime.p12 (key id pf-rig-tls),
#    secrets.env with runtime_tls_password and federation_operator_client_secret
python3 tools/profile.py author <this dir>/observatory-rig.yaml --out $OUT --keep --name pf-rig-author \
  --admin-port 29999 --material-dir $MAT --secrets $MAT/secrets.env          # from pf-agentic-identity, PyYAML needed
python3 overlay.py --admin https://localhost:29999 --author-env $OUT/author.env --manager observatoryRigJwt \
  --audience https://devops.mydigitalid.info/runner --out $OUT/data.zip
age -r "$(cat age-recipient.txt)" -o $OUT/data.zip.age $OUT/data.zip && rm -P $OUT/data.zip   # a new age key needs PF_ARCHIVE_AGE_KEY reset
./compose-context.sh $OUT/data.zip.age
( cd .context && railway up --ci --no-gitignore -p d1b703dd-487d-4439-869e-023a5b0a8da0 -e production -s pingfederate )
```

Then register the agent types ([`../pingfederate/agents/README.md`](../pingfederate/agents/README.md), with
`$PF` = `https://pingfederate-production.up.railway.app` and `Authorization: Bearer $OIDF_AUTHORITY_ADMIN_TOKEN`), and
subordinate the observatory: with `self_anchor` the anchor is this PF, so the observatory's entity is enrolled
here, not at a trust controller. A restart forgets both (in memory): register again.

## Known gaps (lean on purpose)

- The access token carries no `authorization_details`: the RAR processor plugin is not in this image and the
  profile tool has no RAR module yet. The observatory decides each grant against the lease and mandate anyway
  (plan, Approach), so tokens carry the identity (`agent_id`, `cnf.jkt`) and the ceiling stays in the attestation.
- The operator API's audience is not the rig token's; operator calls use the static bearer.
- SCIM `AgentInstances` and CAEP cover PF's own attester's registers, which hold none of the rig's instances.
