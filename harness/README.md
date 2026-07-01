# Attestation client-auth test harness

Exercises the OAuth 2.0 Attestation-Based Client Authentication module
(`draft-ietf-oauth-attestation-based-client-auth-09`) — both the deployed
servlet and the verification logic.

## Pieces

| File | What it does | Needs |
|------|--------------|-------|
| `probe-challenge.sh` | Contract test of the live challenge endpoint (status, `no-store`, JSON shape, uniqueness) | `curl` |
| `AttestationFlowHarness.java` + `run.sh` | `selfverify`: runs the real `ClientAttestationVerifier` in-process (PoP + DPoP accepted, tampered key rejected). `live`: fetches a real challenge from a deployed instance and mints a full attestation + PoP + DPoP, printing the headers and a ready-to-run `curl`. | JDK 17+, jose4j; `selfverify` also needs the built module jar |

`run.sh` auto-resolves jose4j, `commons-logging`, `slf4j-api`, jackson from `~/.m2`,
and the module jar from `../target/`. Override with `JOSE4J_JAR` / `MODULE_JAR` / etc.

## Build the module jar (needed for `selfverify`)

```bash
# from repo root, with the PF 13.0.3 SDK jars installed in ~/.m2 (see ../README.md "Build")
mvn -Dassembly.skipAssembly=true -Dpmd.skip=true -Dcheckstyle.skip=true package
```

## Run

```bash
# 1) endpoint contract test (defaults to the Railway runtime URL)
harness/probe-challenge.sh
harness/probe-challenge.sh https://<host>:<port>/oidf

# 2) in-process verification with the real verifier — no network, no PF
harness/run.sh selfverify

# 3) mint a real request against a deployed instance
harness/run.sh live https://<host>:<port>/oidf
harness/run.sh live https://<host>:<port>/oidf <tokenEndpoint> <clientId>
```

## Deployment context path

The module is deployed as `oidf.war`, so PingFederate's runtime auto-deploys it at
context path **`/oidf`** on the runtime port (9031). The challenge endpoint is therefore
`https://<host>:9031/oidf/federation/attestation-challenge`. (Deploy the WAR as
`ROOT.war`, or merge the classes into `pf-runtime.war`'s `WEB-INF/lib`, to serve the
endpoints at the server root instead.)

## Completing the token-endpoint flow

`live` mode produces wire-correct `OAuth-Client-Attestation` / `-PoP` (and `DPoP`)
headers, but the token endpoint only honours them once PingFederate is configured with:

1. an OAuth AS (issuer + a grant the client can use),
2. the client registered as a **public client** with `attestation_required=true`
   (via `POST /oidf/federation/register`, or manually), and
3. an OAuth **token-endpoint issuance criterion** (OGNL) calling
   `@com.pingidentity.ps.oidf.servlet.clientregistration.utils.ClientAttestationUtils@validateClientAttestation(#this)`.

See the repo `README.md` ("Enabling attestation-based client authentication").
