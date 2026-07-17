# pf-oidf-modules

PingFederate add-on modules implementing **OpenID Federation** (trust anchor / intermediate /
leaf endpoints, explicit client registration, and runtime trust-chain validation) plus
**OAuth 2.0 Attestation-Based Client Authentication** with DPoP.

- OpenID Federation 1.0 (entity statements, subordinate fetch, list, resolve, explicit registration)
- Runtime trust-chain validation of `private_key_jwt` client assertions
- **Attestation-based client authentication** — `attest_jwt_client_auth` with the
  `attestation_pop_jwt` and `dpop_combined` proof-of-possession methods
  ([draft-ietf-oauth-attestation-based-client-auth-10](https://www.ietf.org/archive/id/draft-ietf-oauth-attestation-based-client-auth-10.html)),
  with the Client Attester trusted via the federation trust chain, a challenge endpoint for
  replay/freshness, and DPoP "combined mode" ([RFC 9449](https://www.rfc-editor.org/rfc/rfc9449))
- **Optional SD-JWT attestation encoding** — the Client Attestation may be presented as an SD-JWT so the
  client selectively discloses only the claims a given AS needs (e.g. one entitlement entry, minimal
  `workload`). Advertised via `client_attestation_formats_supported` and gated per client by the
  `attestation_format` extended property. See [docs/sd-jwt-attestation.md](docs/sd-jwt-attestation.md).

> The modules **augment** PingFederate's native client authentication: PingFederate identifies the
> client, and a static utility invoked from **OAuth token-endpoint issuance criteria** performs the
> federation / attestation checks. There is no separate client-authentication plugin SPI in PF.

---

## Contents
- [Requirements](#requirements)
- [Repository layout](#repository-layout)
- [Build](#build)
- [Deploy to PingFederate](#deploy-to-pingfederate)
- [Endpoints](#endpoints)
- [Configuration](#configuration)
- [Enabling attestation-based client authentication](#enabling-attestation-based-client-authentication)
- [Client request format](#client-request-format)
- [Testing](#testing)
- [Security notes & limitations](#security-notes--limitations)

---

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17+ (the POM sets `maven.compiler.release=17`; the enforcer requires Java 11+) |
| Maven | 3.9.0+ |
| PingFederate | A version whose SDK matches `version.server-sdk` in [`pom.xml`](pom.xml) (currently `13.0.0.3`) |

**Runtime (non-`provided`) dependencies** that must be present on the PingFederate classpath:
`jose4j` (0.9.6) and Jackson `jackson-core` / `jackson-databind` (and the transitive
`jackson-annotations`). The PingFederate SDK, Servlet API, Apache HttpClient and Spring are `provided`
(supplied by the PF runtime).

---

## Repository layout

> **Note:** the sources in this tree were produced by decompilation and live at the repository root
> under `com/…`, **not** under `src/main/java`. Maven's default build expects `src/main/java`. Before
> building, move the package tree into the standard layout (tests are already under `src/test/java`):
>
> ```bash
> mkdir -p src/main/java
> mv com src/main/java/
> ```

```
src/main/java/com/pingidentity/ps/oidf/
  common/                     # framework-agnostic core (JWT, trust chain, attestation, DPoP, caches)
  servlet/trustanchor/        # federation endpoints + entity-configuration metadata
  servlet/clientregistration/ # explicit registration + runtime hooks (utils/)
  servlet/attestation/        # attestation challenge endpoint
src/test/java/...             # JUnit 5 tests for the framework-agnostic core
pom.xml
```

---

## Build

The PingFederate SDK artifacts (`pingfederate:pf-protocolengine`, `com.pingidentity.pingfederate:pingfederate-sdk`)
are `provided` and must be resolvable. They are **not** on Maven Central. Either configure access to your
internal repository or install them from your PingFederate install into your local repo, e.g.:

```bash
mvn install:install-file -Dfile="$PF_HOME/pingfederate/server/default/lib/pf-protocolengine.jar" \
  -DgroupId=pingfederate -DartifactId=pf-protocolengine -Dversion=13.0.0.3 -Dpackaging=jar
mvn install:install-file -Dfile="$PF_HOME/sdk/lib/pingfederate-sdk.jar" \
  -DgroupId=com.pingidentity.pingfederate -DartifactId=pingfederate-sdk -Dversion=13.0.0.3 -Dpackaging=jar
```

Then build the jar:

```bash
mvn clean package -Dassembly.skipAssembly=true
```

The result is `target/pf-oidf-modules-0.0.1-SNAPSHOT.jar`.

**Build notes**
- `-Dassembly.skipAssembly=true` skips the `maven-assembly-plugin` step, whose descriptor
  (`build-tools/assembly.xml`) is not included in this tree. Restore `build-tools/` if you want the
  distribution zip.
- `mvn verify`/`install` additionally run PMD and Checkstyle, which reference configs under
  `build-tools/`. Provide those configs, or skip with `-Dpmd.skip=true -Dcheckstyle.skip=true`.
- To run only the unit tests: `mvn test` (these cover the framework-agnostic core and need no PF SDK).

---

## Deploy to PingFederate

1. **Copy jars** to `"$PF_HOME"/pingfederate/server/default/deploy`:
   - `pf-oidf-modules-0.0.1-SNAPSHOT.jar`
   - `jose4j-0.9.6.jar`, `jackson-core-*.jar`, `jackson-databind-*.jar`, `jackson-annotations-*.jar`
     (omit any already provided by your PF version, but verify versions are compatible).
2. **Register the servlets / URL mappings** using the same mechanism you already use for this module's
   existing endpoints (`/.well-known/openid-federation`, `/federation/register`, …). The new
   attestation challenge endpoint is `ClientAttestationChallengeServlet`
   (`/federation/attestation-challenge`); add it alongside the existing ones, including any servlet
   `init-param`s from the [Configuration](#configuration) tables.
3. **Restart** the PingFederate runtime node(s) and confirm startup is clean in
   `server/default/log/server.log`.
4. **Verify metadata** — fetch the entity configuration and confirm the new advertisements:
   ```bash
   curl -s https://<pf-host>:9031/.well-known/openid-federation | cut -d. -f2 | base64 -d | jq .metadata.openid_provider
   ```
   You should see `attest_jwt_client_auth` in `token_endpoint_auth_methods_supported`,
   `attestation_pop_jwt` and `dpop_combined` in `client_attestation_pop_methods_supported`,
   the `*_signing_alg_values_supported` lists, and `challenge_endpoint`.

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/.well-known/openid-federation` | Entity configuration (now advertises attestation support) |
| GET | `/federation/entity`, `/federation/fetch`, `/federation/list`, `/federation/resolve` | Federation operations |
| POST | `/federation/register` | Explicit client registration (`entity-statement+jwt` or `trust-chain+json`) |
| POST | `/federation/attestation-challenge` | **New** — issues a one-time challenge; returns `{"attestation_challenge","expires_in"}` with `Cache-Control: no-store` |

---

## Configuration

### Federation servlet (`OpenIdFederationServlet` → `FederationConfiguration`)

| init-param | Default | Notes |
|------------|---------|-------|
| `trustAnchorIssuers` | — (**required**) | Comma-separated trust anchor entity IDs |
| `subordinates` | empty | Comma-separated subordinate entity IDs for `/federation/list` |
| `trustControllerHost` | — | Base URL of the trust controller |
| `ignoreSslErrors` | `false` | Disable TLS verification for federation fetches (**dev only**) |
| `signingAlgorithm` | `RS256` | `RS256` or `PS256` |
| `corsEnabled` / `corsAllowOrigin` / `corsAllowMethods` / `corsAllowHeaders` / `corsMaxAge` | see code | CORS for federation GETs |
| `tokenEndpointAuthMethodsSupported` | `private_key_jwt,attest_jwt_client_auth` | Advertised auth methods |
| `clientAttestationPopMethodsSupported` | `attestation_pop_jwt,dpop_combined` | Advertised PoP methods (`client_attestation_pop_methods_supported`) |
| `clientAttestationSigningAlgValuesSupported` | `RS256,PS256,ES256` | Advertised attestation algs |
| `clientAttestationPopSigningAlgValuesSupported` | `ES256,RS256,PS256` | Advertised PoP algs |
| `dpopSigningAlgValuesSupported` | `ES256,RS256,PS256` | Advertised DPoP algs |
| `clientAttestationFormatsSupported` | `jwt,sd-jwt` | Advertised attestation encodings (`client_attestation_formats_supported`) |
| `attestationChallengeEndpointEnabled` | `true` | Advertise `challenge_endpoint` in metadata |

### Registration servlet (`OpenIdRegistrationServlet` → `RegistrationConfiguration`)

| init-param | Default | Notes |
|------------|---------|-------|
| `trustControllerHost` | — | Base URL of the trust controller |
| `ignoreSslErrors` | `false` | **dev only** |
| `subordinateStatementCacheMaxEntries` | `256` | `-1` for unbounded |
| `trustChainEntryMaxAgeSeconds` | `60` | Pre-filter for stale chain entries |
| `signingAlgorithm` | `RS256` | `RS256` or `PS256` |
| `acceptedSigningAlgorithms` | empty (any) | Comma-separated allow-list for chain verification |

### Attestation challenge servlet (`ClientAttestationChallengeServlet`)

| init-param | Default | Notes |
|------------|---------|-------|
| `challengeTtlSeconds` | `300` | Challenge lifetime |
| `challengeCacheMaxEntries` | `8192` | Bound on outstanding challenges |
| `replayCacheMaxEntries` | `8192` | Bound on the shared `jti` replay cache |

### Per-client tuning (client **extended properties**, read by the runtime hook)

| Extended property | Default | Effect |
|-------------------|---------|--------|
| `attestation_required` | set to `true` at registration for attestation clients | Marks the client as requiring attestation |
| `attestation_pop_max_age` | `300` | Max age (s) of the PoP JWT `iat` |
| `attestation_dpop_max_age` | `300` | Max age (s) of the DPoP proof `iat` |
| `attestation_clock_skew` | `60` | Allowed clock skew (s) |
| `attestation_challenge_required` | `false` | Require a server-issued challenge |
| `attestation_expected_htu` | request URL | Pin the DPoP `htu` (use behind a reverse proxy) |
| `attestation_accepted_algs` | RS/PS/ES/EdDSA | Allowed attestation signing algs |
| `attestation_pop_algs` | RS/PS/ES/EdDSA | Allowed PoP signing algs |
| `attestation_dpop_algs` | RS/PS/ES/EdDSA | Allowed DPoP signing algs |
| `attestation_format` | accept both | `jwt` = plain only · `sd-jwt` = require SD-JWT · `either` (default) |

---

## Enabling attestation-based client authentication

1. **Register the client** at `/federation/register` with RP metadata
   `token_endpoint_auth_method` = `attest_jwt_client_auth` (either PoP method — the mode is chosen
   per request by which proof header the client presents). The
   module registers it as a **public client** (`ClientAuthenticationType.NONE`) and sets the
   `attestation_required=true` extended property. (Other clients keep `private_key_jwt`.)

2. **Add an OAuth token-endpoint issuance criterion** (OGNL) that calls the runtime hook. Mirror how
   `OIDFederationUtils.validateTrustChain` is already wired, using the static-method form:

   ```ognl
   @com.pingidentity.ps.oidf.servlet.clientregistration.utils.ClientAttestationUtils@validateClientAttestation(#this)
   ```

   The hook returns `true` only when the `OAuth-Client-Attestation` header plus a valid PoP
   (`OAuth-Client-Attestation-PoP`) **or** DPoP proof verify, the attester chains to a configured trust
   anchor, the PoP/DPoP key matches the attestation `cnf`, replay/challenge checks pass, and (if
   present) the `client_id` matches the attestation `sub`.

   Because issuance criteria are ANDed, gate it so non-attestation clients still pass — evaluate the
   attestation hook only when `attestation_required` is set, otherwise fall back to your existing
   `validateTrustChain` criterion.

3. Clients fetch a challenge (when `attestation_challenge_required=true`) from
   `POST /federation/attestation-challenge` and echo it in the PoP `challenge` claim (or the DPoP
   `nonce`).

---

## Client request format

**PoP-JWT mode** (PoP method `attestation_pop_jwt`) — token request carries two headers:

```
POST /as/token.oauth2 HTTP/1.1
OAuth-Client-Attestation: <Client Attestation JWT>      typ=oauth-client-attestation+jwt
OAuth-Client-Attestation-PoP: <PoP JWT>                 typ=oauth-client-attestation-pop+jwt
```

- **Attestation JWT**: `iss`=Attester, `sub`=`client_id`, `exp`, `cnf.jwk`=instance public key.
- **PoP JWT** (signed by the instance key): `aud`=AS issuer/token endpoint, `jti`, `iat`, optional `challenge`.

**DPoP combined mode** (PoP method `dpop_combined`) — the DPoP proof *is* the PoP (no PoP header):

```
POST /as/token.oauth2 HTTP/1.1
OAuth-Client-Attestation: <Client Attestation JWT>
DPoP: <DPoP proof>                                      typ=dpop+jwt, jwk == attestation cnf
```

The DPoP `jwk` header MUST equal the attestation `cnf` key; `htm`/`htu` must match the request; a
server challenge (if any) travels in the DPoP `nonce` claim.

---

## Testing

```bash
mvn test
```

The JUnit 5 suite (`src/test/java/...`) covers the framework-agnostic core — the verifier (PoP & DPoP
happy paths, expired attestation, wrong audience, key mismatch, replay, private-key rejection, challenge
handling, `client_id` mismatch), DPoP proof validation, federation-based attester-key resolution, the
replay cache, the challenge service, and JWK thumbprint binding. These tests need no PingFederate SDK.

---

## Security notes & limitations

- **Trust** in a Client Attester is established only by a successful OpenID Federation trust chain to a
  configured trust anchor (`FederationAttesterKeyResolver`). A dedicated
  `metadata.oauth_client_attester.jwks` block is preferred if published, otherwise the entity's
  chain-validated `jwks`.
- **Key binding**: PoP/DPoP keys are bound to the attestation `cnf` by RFC 7638 thumbprint; `none` and
  symmetric algorithms are rejected; private-key material in `cnf`/`jwk` is rejected.
- **Replay/freshness**: a bounded sliding-window `jti` cache plus an `iat` window, and one-time
  server-issued challenges. **State is per-node** — a clustered PingFederate deployment should replace
  `AttestationChallengeService` / `AttestationReplayCache` (held in `AttestationSupport`) with a shared
  cluster-aware store.
- The response-header challenge mechanism (draft §6.2) and `use_attestation_challenge` on the token
  error are **not** emitted by the issuance-criteria hook (it has no response object). Clients obtain
  challenges from the challenge endpoint (§6.1).
- Behind a reverse proxy, set `attestation_expected_htu` per client so the DPoP `htu` check uses the
  public token-endpoint URL rather than `HttpServletRequest.getRequestURL()`.
- General DPoP sender-constraining of access tokens at the resource server is handled by PingFederate
  natively and is out of scope here; this module implements DPoP only as the combined-mode client-auth
  PoP.
- Confirm `ClientAuthenticationType.NONE` exists in your PingFederate SDK version (used for public
  clients); `PRIVATE_KEY_JWT` is unchanged from the original module.
- **Decompiled source**: this tree was decompiled; a few unrelated pre-existing compile artifacts were
  corrected (`TrustChainValidator`, `HttpTrustControllerGateway`, `FederationService`). Prefer the
  original source if you have it.
```
