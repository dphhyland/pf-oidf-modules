# SSF Transmitter + Receiver (CAEP/RISC) for PingFederate

An OpenID Shared Signals Framework 1.0 **transmitter and receiver** implemented as PingFederate
servlets. Transmitter: PF emits CAEP/RISC Security Event Tokens (SETs) about what it observes, with
spec-proper stream management, push (RFC 8935) and poll (RFC 8936) delivery, an optional Kafka
fan-out, PF-native JDBC persistence, and receiver authentication via PF's own OAuth tokens.
Receiver: PF accepts SETs from another transmitter, verifies them, and acts (grant revocation) —
see [Receiver (inbound SETs)](#receiver-inbound-sets).

Normative refs: OpenID SSF 1.0, CAEP 1.0, RISC 1.0, RFC 8417 (SET), RFC 8935 (push), RFC 8936 (poll),
RFC 9493 (subject identifiers), RFC 7662 (introspection).

## Endpoints

| Path | Purpose |
|---|---|
| `GET /.well-known/ssf-configuration` | transmitter metadata (issuer, jwks_uri, delivery methods, endpoints) |
| `POST/GET/PATCH/DELETE /ssf/streams` | stream CRUD |
| `GET/POST /ssf/status` | stream status (enabled/paused/disabled) |
| `POST /ssf/subjects:add` · `POST /ssf/subjects:remove` | add/remove a stream subject |
| `POST /ssf/verify` | request a verification SET (echoes `state`) |
| `POST /ssf/poll?stream_id=<id>` | RFC 8936 poll delivery (`maxEvents`/`returnImmediately`/`ack`) |

SETs are signed with PF's active JWKS signing key (`typ: secevent+jwt`), so a receiver verifies them
against the transmitter's advertised `jwks_uri` = `<issuer>/pf/JWKS`.

Receiver authentication: every `/ssf/*` request carries an OAuth **bearer token PF itself issued**
(client_credentials, scope `ssf.manage` by default), validated by PF token introspection (RFC 7662).

## Configuration (init-param · system property · env var)

Each setting resolves from, in order: the servlet **`init-param`** (web.xml), the **system property**
`oidf.ssf.<name>` (PingFederate loads `run.properties` entries as system properties), then the **env var**
`OIDF_SSF_<UPPER_SNAKE(name)>`. So when the module is annotation-mapped inside `pf-runtime.war` (no web.xml),
configure it via `run.properties` or Railway env vars — e.g. `OIDF_SSF_ISSUER`,
`OIDF_SSF_KAFKA_BOOTSTRAP_SERVERS`, `OIDF_SSF_INTROSPECTION_CLIENT_SECRET`.

Settings: `issuer` (**required — setting it turns the transmitter on at boot**), `signingAlgorithm`
(RS256/PS256), `basePath` (default `/ssf`), `dataStoreId`, `receiverScope` (default `ssf.manage`),
`introspectionEndpoint`/`introspectionClientId`/`introspectionClientSecret`, `pushRetryMaxAttempts`,
`pushRetryBackoffSeconds`, `pollMaxEvents`, `setTtlSeconds`, `defaultEventTypes`, `verificationEventEnabled`,
and the Kafka knobs below.

**Fail-soft:** if `issuer` is unset, the SSF servlets stay disabled (their endpoints 500) but PF is
unaffected — the transmitter never breaks the runtime web app. When `issuer` is set, the config servlet
(load-on-startup) configures the transmitter at boot, so the logout filter emits immediately. The
logout → `caep.session-revoked` signal needs only `OIDF_SSF_ISSUER`; receiver-authenticated endpoints
(stream mgmt / poll / SCIM) also need the introspection client credentials.

## Persistence — PF-configured JDBC data store

Set `dataStoreId` to a PingFederate-configured **JDBC data store** id and the transmitter persists stream
configs, subjects, and undelivered/unacked SETs there — surviving restarts and shared across a cluster.
Connections come from PF's own pool (`com.pingidentity.access.DataSourceAccessor`); the module never opens
its own pool. Leave `dataStoreId` blank to use the in-memory store (per-node, **not** cluster-safe, lost on
restart — the same caveat as the attestation caches; dev/single-node only).

`storeDialect` selects the persistence layout behind that data store:

- **`tables`** (default) — the module's own three `ssf_*` tables below, DDL applied on boot.
- **`ldm`** — the **ID Partners Identity Object Model** (Postgres JSONB entry store): streams, subjects,
  and pending SETs become `ssfStream` / `ssfStreamSubject` / `ssfPendingSet` object-class entries
  (`LdmSsfStore`). Stream id = `entry_uuid`; membership is containment (`parent_id` = stream) with
  `subject_id` = the RFC 9493 canonical key — the same subject key the model's grants and authorisation
  records use, so stream membership joins to the identity's wider state. The schema is owned by the model
  repo's migration workflow (`migrations/0001-add-shared-signals-ssf.sql` there registers the classes,
  vocabularies, and queue indexes); this store never creates tables. Postgres-specific.

The store applies this DDL on boot if the tables are absent (portable subset; tune types per engine):

```sql
CREATE TABLE IF NOT EXISTS ssf_streams (
  stream_id VARCHAR(64) PRIMARY KEY,
  audience VARCHAR(1024) NOT NULL,
  delivery_method VARCHAR(64) NOT NULL,
  push_endpoint_url VARCHAR(2048),
  push_auth_header VARCHAR(4096),
  events_requested VARCHAR(8192),
  events_delivered VARCHAR(8192),
  status VARCHAR(16) NOT NULL,
  status_reason VARCHAR(1024),
  created_at BIGINT,
  updated_at BIGINT);

CREATE TABLE IF NOT EXISTS ssf_stream_subjects (
  stream_id VARCHAR(64) NOT NULL,
  subject_key VARCHAR(1024) NOT NULL,
  subject_json VARCHAR(4096) NOT NULL,
  PRIMARY KEY (stream_id, subject_key));

CREATE TABLE IF NOT EXISTS ssf_pending_sets (
  jti VARCHAR(64) NOT NULL,
  stream_id VARCHAR(64) NOT NULL,
  subject_key VARCHAR(1024),
  event_type VARCHAR(256),
  set_jws VARCHAR(16384) NOT NULL,
  issued_at BIGINT,
  expires_at BIGINT,
  delivery_attempts INTEGER DEFAULT 0,
  next_attempt_at BIGINT,
  PRIMARY KEY (jti));
```

## Push delivery (RFC 8935)

A background executor POSTs each enabled push stream's queued SETs to its endpoint with
`Content-Type: application/secevent+jwt` (+ the stream's authorization header). 2xx acks the SET; a `400`
(malformed SET) drops it; anything else retries with exponential backoff. After `pushRetryMaxAttempts`
failures the stream is **dead-lettered** — flipped to `paused` with the reason recorded — so a broken
receiver surfaces instead of silently burning retries.

## Kafka fan-out (optional)

When `kafkaEnabled=true`, every emitted SET is also published to Kafka. The Kafka client is loaded purely
by **reflection**, so the module has no `kafka-clients` compile dependency and loads zero Kafka classes when
disabled — supply the (shaded) producer jar on PF's classpath at deploy time.

Config: `kafkaBootstrapServers`, `kafkaTopic` (default `sse-events`), `kafkaSecurityProtocol`
(`PLAINTEXT`/`SASL_SSL`/…), `kafkaSaslMechanism`, `kafkaSaslUsername`, `kafkaSaslPassword`.

Message: **key** = the subject identifier; **value** = a JSON envelope carrying the full signed SET so a
consumer can verify it independently against the transmitter's `jwks_uri`:

```json
{
  "type": "https://schemas.openid.net/secevent/caep/event-type/session-revoked",
  "subject": "email:alice@example.com",
  "set": "<compact JWS>",
  "iat": 1721000000
}
```

## Event sourcing

PF's runtime event hooks call `SsfEventBridge` (best-effort — it never breaks PF's primary flow) to source
SETs: session revoked/logout → `caep.session-revoked`; credential change → `caep.credential-change`;
account disabled/enabled (e.g. from provisioning) → RISC `account-disabled`/`account-enabled`. Each event
fans out to every ENABLED stream that delivers the event type and holds the subject.

### Logout → `caep.session-revoked` (wired)

`LogoutEventFilter` filters PF's OIDC end-session endpoint (`/idp/init_logout.openid`): it reads the subject
from the request's `id_token_hint` (or a back-channel `logout_token`, or an explicit `sub`), lets PF perform
the logout, then calls `SsfEventBridge.onSessionRevoked(...)`. It is fail-open — sign-out proceeds even if
extraction or signalling throws. Like `TokenEndpointAutoRegistrationFilter`, that endpoint is served by PF's
core `pf-runtime.war` (a different context from the module's `oidf.war`), so the filter is registered in
**`pf-runtime.war`'s `WEB-INF/web.xml`**:

```xml
<filter>
  <filter-name>SsfLogoutSignal</filter-name>
  <filter-class>com.pingidentity.ps.oidf.servlet.ssf.LogoutEventFilter</filter-class>
</filter>
<filter-mapping>
  <filter-name>SsfLogoutSignal</filter-name>
  <url-pattern>/idp/init_logout.openid</url-pattern>
</filter-mapping>
```

The **build-in-CI** deploy applies this automatically: `deploy/pingfederate/build/assemble-pf-runtime-war.sh`
injects the module jar into `pf-runtime.war`'s `WEB-INF/lib` **and** this filter mapping into its `web.xml`
(idempotent, existing filters preserved). The alternative loose-jar `Dockerfile` deploy (stock `pf-runtime.war`
+ `deploy/*.jar`) does not reassemble the runtime war, so it would need to adopt the assembled war to pick up
the filter.

Other PF events (credential change, provisioning-driven disable/enable) call the corresponding
`SsfEventBridge` methods from their own hook (an OGNL criterion, a provisioning notification, or the SCIM
servlet, which already emits RISC `account-disabled` on deprovision).

## SCIM subject management

Provisioning drives who is monitored. `POST`/`PUT` a SCIM user carrying the SSF extension to make it a
subject of the named streams; `active:false` or `DELETE` removes it everywhere and emits RISC
`account-disabled`. Wire `/ssf/scim/v2/Users` as an inbound SCIM target in PF like any SCIM app.

```sh
# provision alice as a subject of stream $SID
curl -sk -X POST "$BASE/ssf/scim/v2/Users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/scim+json' -d '{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User",
              "urn:ietf:params:scim:schemas:extension:ssf:2.0:Subject"],
  "userName": "alice",
  "emails": [{"value": "alice@example.com", "primary": true}],
  "urn:ietf:params:scim:schemas:extension:ssf:2.0:Subject": {"streams": ["'"$SID"'"]}
}'
# deprovision (removes from all streams + emits RISC account-disabled); id is the subject canonical key
curl -sk -X DELETE "$BASE/ssf/scim/v2/Users/email:alice@example.com" -H "Authorization: Bearer $TOKEN"
```

## Receiver flow (curl) — acceptance walkthrough

`$BASE` is where the SSF servlets serve (e.g. `https://<host>:9031/oidf`); `$TOKEN` is a PF-issued bearer
with scope `ssf.manage` (client_credentials). `harness/probe-ssf.sh` runs this end-to-end.

```sh
# 1. read transmitter metadata
curl -sk "$BASE/.well-known/ssf-configuration" | jq

# 2. create a poll stream
STREAM=$(curl -sk -X POST "$BASE/ssf/streams" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "aud": "https://receiver.example.com",
  "delivery": {"method": "urn:ietf:rfc:8936"},
  "events_requested": ["https://schemas.openid.net/secevent/caep/event-type/session-revoked"]}')
SID=$(echo "$STREAM" | jq -r .stream_id)

# 3. add a subject
curl -sk -X POST "$BASE/ssf/subjects:add" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"stream_id":"'"$SID"'","subject":{"format":"email","email":"alice@example.com"}}'

# 4. request a verification SET (echoes state)
curl -sk -X POST "$BASE/ssf/verify" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"stream_id":"'"$SID"'","state":"check-123"}'

# 5. poll it back
POLL=$(curl -sk -X POST "$BASE/ssf/poll?stream_id=$SID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"maxEvents":10,"returnImmediately":true}')
echo "$POLL" | jq
JTI=$(echo "$POLL" | jq -r '.sets | keys[0]')

# 6. ack it — the next poll no longer returns it
curl -sk -X POST "$BASE/ssf/poll?stream_id=$SID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ack":["'"$JTI"'"]}'
```

With push delivery configured instead (`"delivery":{"method":"urn:ietf:rfc:8935","endpoint_url":"…"}`), a
PF logout for a subject results in a signed `caep.session-revoked` SET POSTed to the receiver within
seconds (and the same SET on the Kafka topic when enabled).

## Harness

- `harness/probe-ssf.sh <base-url> [bearer-token]` — contract-tests `/.well-known/ssf-configuration`
  (asserts the SSF-mandated fields), and, when a bearer token is supplied, runs the full create → add →
  verify → poll → ack flow above and checks the verification SET comes back.
- `harness/run.sh ssf-selfverify` — mints a CAEP `session-revoked` SET in-process with the module's
  `SetMinter` and verifies its signature + claims (typ `secevent+jwt`, `events`, `sub_id`) — no PF, no network.

## Receiver (inbound SETs)

The module is also an SSF **receiver**: it accepts SETs from another transmitter, verifies them, and acts.

- **Push endpoint (RFC 8935):** `POST /ssf/receiver/events` (`application/secevent+jwt`) — verified
  against the transmitter's JWKS (`SetVerifier`, kid-rotation refresh), deduped by `jti` (idempotent 202),
  spec `{"err": ...}` errors. Optionally bearer-gated. `GET` on the same path lists recent received events.
- **Poll client (RFC 8936):** set `receiverPollUrl` (+`receiverPollToken`) to pull from a remote
  transmitter's poll endpoint — poll → receive → ack next cycle; unverifiable SETs are acked (no
  redelivery loops).
- **Actions:** a verified CAEP `session-revoked` / RISC `account-disabled` /
  `account-credential-change-required` **revokes the subject's OAuth grants in PF**
  (`AccessGrantManagerAccessor`); disable with `receiverActionsEnabled=false`.
- **Registration:** `ReceiverStreamClient` registers this receiver at a remote transmitter (create
  push/poll stream, add subjects, request verification).

Config (`OIDF_SSF_RECEIVER_*`): `receiverExpectedIssuer` (turns the receiver on), `receiverJwksUrl`
(default `<issuer>/pf/JWKS`), `receiverAudience`, `receiverEndpointAuthToken`, `receiverJwksCacheSeconds`,
`receiverInsecureTls`, `receiverPollUrl`, `receiverPollToken`, `receiverPollIntervalSeconds`,
`receiverActionsEnabled`.

Proven live by the demo's loopback stage: a logout on the demo PF → the transmitter's push executor
POSTs the signed SET to the same PF's receiver endpoint → verified against `/pf/JWKS` → grant-revocation
action runs.

## Demo

The `idp-pingfed-ssf-servelet` repo is the runnable demo of all of the above: a docker-compose stack
(PF 13.0.3 with the module merged into `pf-runtime.war` + a Postgres carrying the Identity Object
Model, `storeDialect=ldm`), an 11-stage end-to-end probe, and a browser UI. The same stack runs
**publicly on Railway**:

- UI: https://ssf-demo-ui-production.up.railway.app
- Transmitter metadata: https://pingfederate-ssf-production.up.railway.app/.well-known/ssf-configuration
