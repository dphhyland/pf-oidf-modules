# Parallel Interoperability Event — OpenID Federation as an alternative trust model for MCP security

**Status:** Proposal / draft for the OpenID AIIM CG
**Author:** David Hyland
**Companion to:** the AIIM CG MCP security interoperability event (CIMD + ID-JAG)

---

## Summary

The AIIM CG MCP security interop event, as currently scoped, is anchored to **CIMD**
(Client ID Metadata Document) and **ID-JAG** (the identity-assertion authorization grant
used for cross-domain identity chaining). Both are load-bearing for securing MCP flows
within and across enterprises.

CIMD and ID-JAG are, in effect, **a way of doing federation** — they answer the question
*"how do a client, an authorization server, a gateway and a server in different
organisations come to trust each other with no prior bilateral setup?"* the web way: a
metadata URL you fetch, an identity-assertion grant you chain, trust derived from TLS and
out-of-band agreement.

**OpenID Federation is an alternative answer to the same question** — the anchored way:
a signed trust chain to a common Trust Anchor, constrained by metadata policy, with
verifiable Trust Marks.

This document proposes running an OpenID Federation interop **in parallel** with the
CIMD/ID-JAG event, against the same MCP actors. The two are peer tracks, not competitors,
and the cross-track comparison is an explicit output: evidence that the same end-to-end MCP
security properties can be achieved under either trust model, so the choice becomes
architectural rather than forced.

---

## Two models of federation (the reason for a parallel track)

| Concern | CIMD + ID-JAG track | OpenID Federation track |
|---|---|---|
| Client identity, no pre-registration | **CIMD** — `client_id` is a URL; AS fetches the client's *self-asserted* metadata | **Entity Configuration + automatic registration (§12)** — `client_id` is an entity identifier; metadata is *signed and policy-constrained* along a chain to a Trust Anchor |
| Basis of trust | TLS + the URL namespace + out-of-band agreement | Cryptographic: signed Subordinate Statements to a common anchor |
| Cross-domain authority | **ID-JAG** — identity-assertion grant chained via token exchange; inter-domain trust assumed/configured | **Trust chain to a shared anchor** establishes AS↔AS trust; the grant is still exchanged, but trust is *derived*, not pre-agreed |
| Metadata constraint | none — client asserts its own | **Metadata policy operators** (`value` / `add` / `default` / `one_of` / `subset_of` / `superset_of` / `essential`) |
| Conformance / eligibility signalling | none defined | **Trust Marks** (issue + verify) |
| Scaling shape | by convention, decentralised, N-to-N | by hierarchy, multilateral, N-to-anchor |
| Sender-constraining (shared) | **DPoP** | **DPoP** — identical, orthogonal to trust model |

Neither is strictly wrong — they trade decentralisation against provable, policy-bound
trust. Enterprises with a supply chain and a compliance obligation tend to want the second;
a fast-moving developer ecosystem tends to want the first. The most useful thing this event
can produce is evidence that *both* deliver the same MCP security properties.

---

## Standards used

- **OpenID Federation 1.0** (Final, Feb 2026) — Entity Configurations, Fetch/List endpoints, Trust Chains, metadata policy, Resolve endpoint
- **OpenID Federation Trust Marks**
- **OpenID Federation automatic client registration** (§12) — the federation-native counterpart to CIMD
- **OAuth 2.0 / OIDC Core** — the flows being secured
- **DPoP (RFC 9449)** — shared with the CIMD/ID-JAG track, so tokens are sender-constrained under either trust model

---

## Interop test architecture

Same MCP actors as the parallel event; the trust plumbing is federation.

```
                    ┌───────────────────┐
                    │   Trust Anchor     │  signs Subordinate Statements,
                    │  (Fetch + List)    │  hosts Resolve
                    └───┬───────────┬────┘
              policy ▼  │           │  ▼ policy
         ┌─────────────┴──┐   ┌─────┴──────────────┐
         │  Intermediate   │   │  Trust Mark Issuer  │
         └───┬─────────────┘   └─────────────────────┘
   trust chain │
   ┌───────────▼─────┐   ┌──────────────┐   ┌──────────────┐   ┌───────────┐
   │   MCP Client     │──►│  OAuth AS     │──►│ MCP Gateway   │──►│ MCP Server │
   │ (entity / leaf)  │   │ (leaf; resolver)  │ (leaf)        │   │ (leaf)     │
   └──────────────────┘   └───────┬──────┘   └──────────────┘   └─────┬─────┘
                                  │  cross-domain trust via common anchor │
                                  └───────────────────────────────────────┘
                                            (federation-derived, replaces bilateral ID-JAG trust)
        AuthZen PDP unchanged — federation establishes WHO; AuthZen decides WHAT
```

---

## Flow steps (mapped one-to-one to the CIMD/ID-JAG flow)

1. **Entities are set up.** Trust Anchor, optional Intermediates, and every MCP actor
   (client, AS, gateway, server) publish a signed **Entity Configuration** at
   `/.well-known/openid-federation` with `authority_hints`.
2. **Client obtains a token — federated CIMD.** The MCP Client's `client_id` is an entity
   identifier. The AS **resolves and validates its trust chain** to a common Trust Anchor
   and applies metadata policy, then issues a DPoP-bound OAuth token. *(Federation
   counterpart to CIMD — same "no pre-registration" outcome, signed and policy-constrained.)*
3. **Fine-grained authorisation.** The MCP Gateway or Server calls the AuthZen PDP —
   unchanged from the other track.
4. **Cross-domain trust — federated ID-JAG.** The MCP Server needs a foreign resource. The
   receiving (foreign) AS establishes trust in the issuing AS by **resolving its trust chain
   to a shared Trust Anchor** (direct, via Intermediate, or via a Resolve endpoint) rather
   than from pre-shared config, then honours the exchanged grant. *(Federation counterpart
   to ID-JAG's cross-domain step.)*
5. **Foreign access.** The MCP Server uses the resulting DPoP-bound token to reach the
   foreign MCP Server.
6. *(optional)* **Trust Mark verification.** Any leaf presents Trust Marks (e.g. "certified
   MCP server", "eligible for payment scope"); the counterpart verifies against the declared
   issuer.

---

## Interoperability testing rules

1. Each participant fulfils at least one role: MCP Client · OAuth AS · MCP Gateway ·
   MCP Server · AuthZen PDP · **Trust Anchor** · **Intermediate** · **Trust Mark Issuer**.
2. Each participant tests at least one capability with a complementary-role participant.
3. At least one box checked to complete.

---

## Interoperability matrix

The MCP role columns are kept identical to the CIMD/ID-JAG matrix so the two can be laid
side by side; the three federation-authority columns are added for this track. `N.A.` where
a role cannot hold a capability.

**Implementation name:** ________________

| Capability / Role | MCP Client | OAuth AS | MCP Gateway | MCP Server | AuthZen PDP | Trust Anchor | Intermediate | Trust Mark Issuer |
|---|---|---|---|---|---|---|---|---|
| **DPoP** *(shared)* | ☐ | ☐ | ☐ | ☐ | N.A. | N.A. | N.A. | N.A. |
| Entity Configuration | ☐ | ☐ | ☐ | ☐ | N.A. | ☐ | ☐ | ☐ |
| Automatic client registration *(↔ CIMD)* | ☐ | ☐ | N.A. | N.A. | N.A. | N.A. | N.A. | N.A. |
| Trust-chain resolution & signature validation | N.A. | ☐ | ☐ | ☐ | N.A. | ☐ | ☐ | N.A. |
| Metadata policy application | N.A. | ☐ | ☐ | ☐ | N.A. | ☐ | ☐ | N.A. |
| Cross-domain trust via common anchor *(↔ ID-JAG)* | N.A. | ☐ | N.A. | ☐ | N.A. | ☐ | ☐ | N.A. |
| Fetch endpoint | N.A. | ☐ | N.A. | N.A. | N.A. | ☐ | ☐ | N.A. |
| List endpoint | N.A. | ☐ | N.A. | N.A. | N.A. | ☐ | ☐ | N.A. |
| Resolve endpoint | N.A. | ☐ | ☐ | ☐ | N.A. | ☐ | ☐ | N.A. |
| Trust Mark issuance | N.A. | N.A. | N.A. | N.A. | N.A. | N.A. | N.A. | ☐ |
| Trust Mark verification | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | N.A. |

---

## Relationship to the CIMD + ID-JAG event

- **Same actors, same security goal, different trust model.** A vendor can bring one MCP
  client/AS/gateway/server and participate in *both* tracks — CIMD + ID-JAG in one, OpenID
  Federation in the other — and produce two matrices.
- **DPoP is shared**, so sender-constrained tokens are common ground regardless of trust
  model.
- **Cross-track comparison is an explicit output.** Where a participant does both, we note
  that the same MCP flow achieved the same properties under either trust model — the
  strongest possible endorsement of the event's "free to choose" premise.
- The two are not mutually exclusive in the real world (federation can even sit under CIMD),
  but for a clean interop result they are tested as **alternatives**, side by side.

---

## What we bring

We can stand up live counterparts today, not just on paper: a distributed OpenID Federation
on PingFederate with a Trust Anchor, intermediates, cross-org trust-chain resolution, a
distributed Resolve endpoint, Trust Marks, and **automatic client registration at token
time** (§12). That lets us be the complementary side for other participants on the
federated-CIMD and federated-ID-JAG pairings, and host the neutral Trust Anchor /
Trust Mark Issuer if the event wants a shared root.

---

## Testing process

Unchanged from the AIIM model, and ideally **run jointly** with the CIMD/ID-JAG event —
same weekly calls, same commit-by date, same mutual sign-off, same demo tables — so
participants can schedule a federation pairing and a CIMD/ID-JAG pairing in the same session
and publish both matrices together.

---

## Potential participants

A starting call-list mapped to every role/capability. Federation support moves fast, so
confidence is flagged rather than asserted. The scarce, gating side is the **federation
authorities and OP/AS resolvers** (left of the matrix); the MCP leaves and PDPs are
plentiful.

**Legend:** ✔ known/strong · ~ plausible/partial · ? confirm current support

### OAuth AS / OpenID Provider (resolver side — the pivotal role)

| Candidate | Fed resolution | Auto-reg (↔CIMD) | Cross-domain (↔ID-JAG) | DPoP | Note |
|---|---|---|---|---|---|
| **Ping (PingFederate/PingOne)** | ✔ | ✔ | ✔ | ✔ | our live stack — can also host TA/Intermediate/Trust Marks |
| **Connect2id (Nimbus)** | ✔ | ~ | ~ | ✔ | federation in server + SDK |
| **Authlete** | ~ | ~ | ~ | ✔ | AS backend, federation features |
| **Gluu / Janssen (Jans)** | ~ | ~ | ? | ✔ | federation in Jans |
| **SUNET / idpy (fedservice)** | ✔ | ✔ | ✔ | ~ | reference OP/RP, R&E production |
| **Italy — SPID/CIE stack (AgID)** | ✔ | ✔ | ✔ | ~ | national federation reference impls |
| **Curity** | ? | ~ | ~ | ✔ | strong OAuth; confirm federation |
| **WSO2 / Asgardeo** | ? | ~ | ~ | ✔ | confirm federation |
| **Keycloak (Red Hat)** | ? (ext) | ~ | ~ | ✔ | federation via community extension only |
| **Stytch / WorkOS / Descope** | ? | ~ (CIMD-style) | ~ | ✔ | active in *MCP OAuth*; likely CIMD-track, federation ? |

### Trust Anchor / Intermediate (federation authority — scarcest)

| Candidate | Note |
|---|---|
| **SUNET / SWAMID** ✔ | longest-running production TA (R&E) |
| **GÉANT / eduGAIN / GARR** ~ | R&E federations migrating to OpenID Federation |
| **Italy — AgID / IPZS / Lepida** ✔ | national TA + intermediates in production |
| **Ping (us)** ✔ | can host the neutral TA/Intermediate for the event |
| **OpenID Foundation** ~ | natural *neutral* anchor + conformance referee |
| **Connect2id** ~ | server can act as TA/Intermediate |

### Trust Mark Issuer

Any of the authorities above (SUNET, AgID, Ping, OIDF). OIDF is the cleanest *neutral*
issuer for "certified MCP server" / "eligible for scope X" marks.

### MCP Client (leaf — federated CIMD side)

| Candidate | Note |
|---|---|
| **Anthropic** ~ | MCP originator; reference client/server |
| **Microsoft (Copilot), Google, AWS (Bedrock AgentCore)** ~ | building MCP clients/agents |
| **Block, Postman, Sourcegraph, Cursor, Zed** ~ | shipping MCP clients |
| **idpy / SUNET RP** ✔ | can act as the federated leaf client |

### MCP Gateway

| Candidate | Note |
|---|---|
| **Kong** ✔ | MCP/AI gateway + OIDC; natural federated RP/gateway |
| **Solo.io (Gloo) / Envoy / Istio** ~ | Envoy-based MCP gateways |
| **Cloudflare** ~ | MCP gateway on Workers |
| **AWS (API Gateway / AgentCore)** ~ | gateway/resource estate |
| **Microsoft (APIM)** ~ | API/MCP gateway |

### MCP Server (leaf)

| Candidate | Note |
|---|---|
| **AWS, Microsoft, Google, Cloudflare** ~ | first-party MCP servers |
| **Anthropic reference servers** ~ | reference targets |
| Any AS vendor above ~ | can expose a protected MCP server as their leaf |

### AuthZen PDP (unchanged from the CIMD/ID-JAG event — deep, interop-proven bench)

Largely the vendors that ran the AuthZen interop already.

| Candidate | Note |
|---|---|
| **PingAuthorize (Ping)** ✔ | our PDP — AuthZen |
| **SGNL** ✔ · **PlainID** ✔ · **Axiomatics** ✔ | enterprise PDPs, AuthZen-active |
| **Aserto / Topaz** ✔ · **Cerbos** ✔ · **Styra / OPA** ✔ | policy engines |
| **AWS Verified Permissions (Cedar)** ~ | Cedar-based PDP |
| **Permit.io** ✔ · **Oso** ~ · **EmpowerID** ~ · **Indykite** ~ · **Cloudentity (SecureAuth)** ~ | AuthZen interop roster |

### Coverage of the gating (federation-specific) capabilities

The rows that decide whether the event has enough complementary pairs:

| Capability | Who can credibly be the counterpart |
|---|---|
| **Entity Configuration** | everyone above who does federation — table stakes |
| **Trust-chain resolution & signature validation** | Ping, SUNET/idpy, Italy stack, Connect2id, Authlete, Gluu/Janssen |
| **Metadata policy application** | Ping, SUNET/idpy, Italy stack, Connect2id |
| **Automatic client registration (↔CIMD)** | Ping, SUNET/idpy, Italy stack, (Gluu/Janssen ~) |
| **Cross-domain trust via common anchor (↔ID-JAG)** | Ping, SUNET/idpy, Italy stack, Connect2id ~ |
| **Fetch / List endpoints** | Ping, SUNET, Italy, Connect2id (the authorities) |
| **Resolve endpoint** | Ping (distributed resolve), SUNET, Italy |
| **Trust Mark issue/verify** | SUNET, AgID, Ping, OIDF |
| **DPoP (shared)** | broad — Ping, Curity, Connect2id, Authlete, Okta/Auth0, Stytch, WorkOS, Descope, WSO2, Gluu/Janssen, Keycloak |

### The honest read

- **Federation depth clusters in the R&E / national-federation world** (SUNET, GÉANT,
  Italy/AgID) plus a few commercial servers (Ping, Connect2id, Authlete, Janssen). That is
  the authority + resolver bench — enough for real pairings, but lock 2–3 of them early
  because they are the scarce side.
- **The MCP-native names (AWS, Kong, Cloudflare, Anthropic, Stytch/WorkOS/Descope) sit on
  the leaf side** — great as clients/gateways/servers, but most will need to *adopt* the
  federation entity-config + chain-resolution behaviour to tick the hard boxes. Several are
  more naturally CIMD/ID-JAG-track today, which is exactly why parallel tracks work: they
  play both.
- **AuthZen PDP bench is already deep and interop-proven** — lowest-risk column.

---

## Open decisions before circulating

1. **How parallel is "parallel"** — same event with two tracks and two matrices under one
   banner (this draft), or a genuinely separate event with its own committee? Same-banner is
   easier to adopt and makes the cross-track comparison free; separate gives federation its
   own identity but risks looking like a competitor.
2. **The comparison claim** — lead with "both deliver identical security properties, so the
   trust model is a free choice" as the up-front thesis, or let the matrices demonstrate it
   and stay neutral.
3. **Who hosts the neutral anchor** — offering to run the Trust Anchor is a strong pull for
   participation but puts us in the referee seat; alternatively hand it to the OpenID
   Foundation conformance suite as the neutral party.
4. **"Identity" in the seed shortlist** — resolve which vendor this refers to
   (Duende IdentityServer? IDnow? another) and slot it precisely.
