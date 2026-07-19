# SSF Transmitter (CAEP/RISC) for PingFederate

An OpenID Shared Signals Framework 1.0 transmitter implemented as PingFederate servlets: PF emits
CAEP/RISC Security Event Tokens (SETs) about what it observes, with spec-proper stream management, push
(RFC 8935) and poll (RFC 8936) delivery, an optional Kafka fan-out, PF-native JDBC persistence, and
receiver authentication via PF's own OAuth tokens.

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

## Configuration (servlet init-params)

`issuer` (required), `signingAlgorithm` (RS256/PS256), `basePath` (default `/ssf`), `dataStoreId`,
`receiverScope` (default `ssf.manage`), `introspectionEndpoint`/`introspectionClientId`/
`introspectionClientSecret`, `pushRetryMaxAttempts`, `pushRetryBackoffSeconds`, `pollMaxEvents`,
`setTtlSeconds`, `defaultEventTypes`, `verificationEventEnabled`, and the Kafka knobs below.

## Persistence — PF-configured JDBC data store

Set `dataStoreId` to a PingFederate-configured **JDBC data store** id and the transmitter persists stream
configs, subjects, and undelivered/unacked SETs there — surviving restarts and shared across a cluster.
Connections come from PF's own pool (`com.pingidentity.access.DataSourceAccessor`); the module never opens
its own pool. Leave `dataStoreId` blank to use the in-memory store (per-node, **not** cluster-safe, lost on
restart — the same caveat as the attestation caches; dev/single-node only).

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

_A curl walkthrough of the receiver flow (read config → create poll stream → add subject → verify →
poll + ack) and the harness live-mode land with Phase 5._
