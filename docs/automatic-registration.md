# Automatic client registration (OpenID Federation §12.1)

The module already supported **explicit** registration (`POST /federation/register` →
`explicit-registration-response+jwt`). This adds **automatic** registration: a federation client that has
*never* called the registration endpoint can obtain a token by presenting its Trust Chain inline, and the
Authorization Server (PingFederate) validates the chain, derives the client's metadata on the fly, and
provisions the client transparently.

Because our federation clients are OAuth `client_credentials` machine/agent clients (not OIDC
authorization-request RPs), §12.1 is realised at the **token endpoint**: the client's `client_assertion`
(`private_key_jwt`) carries its chain in the `trust_chain` JWS header. PingFederate needs a persisted client
record to authenticate a token request, so a **servlet filter runs before PF's client authentication** and
just-in-time provisions the client from its resolved federation metadata (`status=auto_registered`). It is
idempotent and fail-open.

## What changed

| Piece | File | Notes |
|---|---|---|
| Advertise both modes | `servlet/trustanchor/FederationService.java` + `FederationConfiguration.java` | `client_registration_types_supported` is now config-driven (init-param `clientRegistrationTypesSupported`), default `["automatic","explicit"]`. |
| Auto-provisioning | `servlet/clientregistration/RegistrationService.java` | New `automaticRegister(trustChain, clientId, opIssuer)`; reuses `buildClient(...)` with `status=auto_registered`; requires the resolved leaf to advertise `client_registration_types` ⊇ `automatic`; idempotent. |
| Token-endpoint filter | `servlet/clientregistration/TokenEndpointAutoRegistrationFilter.java` | **New.** Extracts the `client_assertion`'s `sub` + `trust_chain` header and auto-provisions before PF authenticates. Fail-open. |
| OAuth-client metadata | `common/TrustChainValidator.java` | Leaf metadata now falls back to the `oauth_client` block when `openid_relying_party` is absent (the correct type for `client_credentials` clients). |
| Read-only client list | `servlet/clientregistration/RegisteredClientsServlet.java` | **New.** `GET /federation/registered-clients` → the federation clients PF holds (`explicit`/`automatic`), for a dashboard. No PF admin API needed. |
| Demo | `harness/ui/{server.py,index.html}` | `/api/pf-clients`, `/api/trust-chain`, `/api/token-federation`; a "Clients registered in PingFederate" panel + a ⚡ "Get a token — automatic registration" step. |

## Deployment wiring (required for the transparent flow)

`/as/token.oauth2` is served by PF's core `pf-runtime.war`, a different servlet context from the module's
`oidf.war`. So the filter must be registered in **`pf-runtime.war`'s `WEB-INF/web.xml`**, and its class (plus
the module classes) must be on `pf-runtime.war`'s classpath — the same single-classloader approach already
used for the OGNL hook (bake `pf-oidf-modules` classes into `pf-runtime.war` rather than a loose
`deploy/*.jar`; see `staging-mirror-and-sdjwt-live` notes).

```xml
<filter>
  <filter-name>OidfAutoRegistration</filter-name>
  <filter-class>com.pingidentity.ps.oidf.servlet.clientregistration.TokenEndpointAutoRegistrationFilter</filter-class>
  <init-param><param-name>trustControllerHost</param-name><param-value>https://YOUR-TRUST-CONTROLLER</param-value></init-param>
  <init-param><param-name>ignoreSslErrors</param-name><param-value>false</param-value></init-param>
  <init-param><param-name>acceptedSigningAlgorithms</param-name><param-value>ES256,RS256</param-value></init-param>
</filter>
<filter-mapping>
  <filter-name>OidfAutoRegistration</filter-name>
  <url-pattern>/as/token.oauth2</url-pattern>
</filter-mapping>
```

`RegisteredClientsServlet` and the metadata change ship in `oidf.war` (annotation-scanned / config-driven) —
no extra wiring. The explicit `/federation/register` path is unchanged.

## Security notes

- `GET /federation/registered-clients` discloses client identifiers and their granted scopes. It only lists
  clients carrying our `status` extended parameter (never PF's own clients), but it is unauthenticated —
  intended for a trusted operator/demo surface. Access-control or disable it in a hardened deployment.
- The filter is **fail-open**: any provisioning error is logged and the request proceeds, so PF then rejects
  an unknown/unauthenticated client exactly as before. A malformed or untrusted chain never yields a client.

## Live staging deployment (2026-07-08) — proven end-to-end

Deployed to the staging `pingfederate-runtime` (via the `idp-paz-authzen-adapter/demo/pingfederate` Docker
context — the real staging source). The full transparent flow **issues a real Bearer token**: present a
`private_key_jwt` carrying the trust chain → the filter validates + auto-provisions the client
(`auto_registered`) → PF authenticates it → the issuance criterion accepts the federation member → token.

Six issues surfaced only live (each fixed):
1. **`ES512`** — the anchor signs subordinate statements with ES512 (P-521); the filter's
   `acceptedSigningAlgorithms` init-param must include it (`ES256,ES384,ES512,RS256,PS256`).
2. **redirect_uris NPE** — PF's `ClientManager` NPEs saving a client with null `redirect_uris`/`response_types`;
   `buildClient` now defaults them to empty lists (fixed in `RegistrationService.java`).
3. **`aud`** — PF's native `private_key_jwt` validator checks `aud` against PF's *configured* base URL
   (`https://localhost:9031`), not the request host. The demo sends `PF_TOKEN_AUD`/`token_aud`.
4. **Issuance criteria** — the `client_credentials` ATM mappings in `data.zip`
   (`oauth-authz-server-settings.xml`) enforce `validateClientAttestation(#this)`; OR'd with
   `OIDFederationUtils@validateTrustChain(#this, false, '<trust-controller>')` (4 mappings) so federation
   clients pass. **This lives in the deploy context's `data.zip`, not this repo.**
5. **scope** — `read_accounts create_opportunity` aren't defined AS scopes → `invalid_scope`; request none (or
   define them).
6. **idempotency** — `automaticRegister` skips an existing client; a re-mint with a *new* key under the same
   `client_id` then mismatches the registered key. Fine for real clients (stable key); for the demo use a
   fresh entity per run, or extend `automaticRegister` to update the jwks.

Known remaining item: the **"Clients in PingFederate" panel reads 0** — the client store is ephemeral
(`/opt/out`, resets per redeploy) and the `oidf.war` list servlet's `getClients()` view doesn't reflect
clients the `pf-runtime.war` filter provisioned (custom `status` extended param likely dropped on reload, or
a cross-context store view). Needs a distinguishing field PF preserves, or serving the list from the runtime
context. Registration + token are unaffected.

## Verification status

- Plugin: compiles offline; **78/78 unit tests pass** including new coverage for `automaticRegister`
  (happy-path / idempotency / refuses-non-automatic) and the filter (extract → provision → proceed /
  fail-open / no-assertion).
- Demo: renders; `/api/pf-clients` degrades gracefully until the plugin is redeployed; `/api/trust-chain`
  resolves a real chain. The end-to-end transparent token flow is verifiable **after** the PF redeploy
  (the filter must be live).
