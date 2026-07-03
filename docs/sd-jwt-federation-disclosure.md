# Federation-gated SD-JWT disclosure

Reveal different Client-Attestation claims to different Authorization Servers based on **where the
AS provably sits in the OpenID Federation** — its trust anchor, the trust marks it holds, and the
metadata the federation lets it declare. OpenID Federation answers *who the AS is and what it is
entitled to receive*; SD-JWT ([[sd-jwt-attestation]]) is the mechanism to reveal only those claims.

## Model: holder-driven, keyed on verifiable AS standing

Disclosure in SD-JWT is chosen by the **holder** (the client instance) at *presentation* time — so
the decision already sits with the client. Federation-gating makes that choice a function of the AS's
resolved federation entity:

```
client instance holds the SD-JWT (full ceiling: all workload fields + all entitlements)
    │
    1. resolve the target AS as a federation entity
    │      AS/.well-known/openid-federation → trust chain → trust anchor   (reuse TrustChainValidator)
    │
    2. read the AS's provable standing from the chain:
    │      • trust anchor it chains to        (which federation / domain)
    │      • trust marks it holds             (accreditations, e.g. region / workload-inspection)
    │      • declared metadata + metadata_policy applied along the chain
    │
    3. evaluate a disclosure policy  → choose the subset of disclosures
    │
    4. present  issuer-JWT + ONLY those disclosures + KB-JWT(sd_hash)
```

The AS never sees more than the holder discloses. If the AS requires a claim it isn't entitled to,
the holder withholds it and the AS's own policy decides whether to proceed or reject — that is the
negotiation.

## Federation signals to key disclosure on (best-fit first)

| Signal | Why it fits | Example policy |
|---|---|---|
| **Trust marks** | A *signed*, chain-anchored statement that the AS meets a criterion — verifiable entitlement, not self-assertion. | Disclose `workload.*` only to an AS holding `tm/workload-inspection`; disclose PII-bearing claims only to `tm/pii-handler:EMEA`. |
| **Trust anchor / authority chain** | Tells you *where the AS sits* — which federation and under which intermediary. | Full entitlement to AS's under the home anchor; region-scoped only to a different anchor. |
| **Declared metadata + `metadata_policy`** | AS declares what it needs; the federation *constrains* what it may request as the chain resolves. | Disclose `AS-requested ∩ federation-allowed ∩ holder-willing`. |
| **Entity type / sub-role** | Coarsest — the kind of entity. | Disclose more to an OP than to a bare RS. |

Trust marks are the most semantically-exact tool ("the AS *is entitled* to see claim X"); trust
anchor is the most direct answer to "*where the AS sits*."

## Maps onto what already exists

- **Trust-chain resolution** — `TrustChainValidator` + `FederationAttesterKeyResolver` already resolve
  and validate chains to a trust anchor (used today for the *attester's* keys). The same machinery
  resolves the **AS's** entity to read its anchor / trust marks.
- **OP metadata already advertises attestation capability** (`client_attestation_formats_supported`).
  The symmetric addition is an AS-side declaration — `client_attestation_claims_required` / a
  disclosure-policy pointer — governed by `metadata_policy`.
- **The demo's disclosure step is already holder-side** — the browser currently picks which workload
  fields + entitlement to disclose via a static `REVEAL` set. Federation-gating replaces that static
  set with `policyReveal(resolvedAsProfile)`.

## Prototype (demo, `harness/ui`)

Holder-side, since disclosure lives with the client. The demo models the AS's federation standing and
drives the SD-JWT presentation from it — the same client, presenting to differently-positioned AS's,
discloses different subsets; PingFederate then sees only what was disclosed.

**Mock AS federation profiles** (stand in for a resolved trust chain):

| Profile | Trust anchor | Trust marks | Policy outcome |
|---|---|---|---|
| Contoso EMEA AS (home) | `acme-federation` | `region:EMEA`, `workload-inspection` | entitlement + **all** workload fields |
| Partner CRM AS (same anchor) | `acme-federation` | `region:EMEA` | entitlement + **minimal** workload (`software_id/version/environment`) |
| External AS (other anchor) | `other-federation` | — | entitlement **only**, no workload |
| Unknown AS (no chain) | *unresolvable* | — | mandatory claims only → under-disclosed (AS may reject) |

**`policyReveal(profile)`** returns which disclosures to present:
- entitlement entry: disclosed when the chain resolves to a trusted anchor;
- each `workload.*` field: disclosed when the AS holds `workload-inspection`, else only the
  low-sensitivity subset for a same-anchor AS, else withheld cross-anchor.

The demo shows the *resolved AS entity* (anchor + trust marks) and, per claim, **why** it was
disclosed or withheld — making the federation→disclosure link tangible.

## Caveats (honest)

- **Trust bootstrapping cuts both ways** — the holder must validate the AS's chain to an anchor *it*
  trusts before trusting the AS's declared needs. Unresolvable → **default to minimal disclosure**.
- **`metadata_policy` was designed to constrain metadata, not "which disclosures."** Using it here
  means expressing disclosure entitlements *as* a governed metadata parameter — legitimate but a slight
  extension; trust marks are the exact tool.
- **Holder-enforced** — the federation defines *entitlement*; it cannot *prevent* a misbehaving holder
  from over-disclosing. It can only let the AS reject under-disclosure.
- **Governs *what* is revealed *to whom* — not unlinkability.** SD-JWT still leaks the attester `iss`
  and correlates across presentations. Pair with per-audience short-lived issuance for cross-domain
  unlinkability. See [[sd-jwt-attestation]].
