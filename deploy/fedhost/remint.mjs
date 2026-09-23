#!/usr/bin/env node
//
// Re-mint the fedhost federation content.
//
// WHY THIS EXISTS. content.<env>.json holds signed JWTs and nothing else: the entity keys that
// signed them are never stored. That is the right call for a file that is committed - but it means
// the content cannot be edited, only re-minted. Two things force a re-mint:
//
//   1. fedhost moves. Every entity's iss/sub is its URL under fedhost, so a new fedhost domain
//      invalidates all 15 configurations at once.
//   2. The trust anchor's key changes. The anchor signs the subordinate statements that put these
//      entities in the federation; a fresh anchor (a new trust-controller volume) knows none of
//      them, so all 10 have to be registered again.
//
// Both happened when the Railway project was rebuilt, twice, and both times this script was written
// from scratch and then thrown away. It lives here now.
//
// WHAT IT MINTS. A fresh ES256 key per entity, then, in dependency order:
//   - tmi first, because it signs the trust marks the other configurations carry;
//   - every entity configuration, self-signed, with URLs re-homed and its new public JWK as `jwks`;
//   - the subordinate statements the fedhost-local authorities issue (as -> agentic-cba, cba-api;
//     other-anchor -> as-external), signed with the issuer's new key over the subject's new key.
//
// The anchor-signed statements are NOT minted here - only the anchor can sign those. --register
// posts each entity's public key to the trust controller's admin API and the anchor mints them.
//
// rp-suspended is deliberately NOT registered: "configuration exists, not enrolled" is the state
// the demo's status-reject gate needs to show. Registering it would silently delete that case.
//
// Usage:
//   node remint.mjs --env staging --fedhost https://fedhost-staging.up.railway.app \
//                   --anchor https://trust-controller-staging.up.railway.app [--register]
//
// Without --register it only rewrites content.<env>.json; with it, it also enrols at the anchor.

import { webcrypto as crypto } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

// ── args ────────────────────────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2)
const arg = (name, dflt) => {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const ENV = arg('env', 'staging')
const NEW_FEDHOST = (arg('fedhost') || '').replace(/\/$/, '')
const NEW_ANCHOR = (arg('anchor') || '').replace(/\/$/, '')
const REGISTER = args.includes('--register')
const LIFETIME_DAYS = Number(arg('days', '365'))

if (!NEW_FEDHOST || !NEW_ANCHOR) {
  console.error('usage: remint.mjs --env <staging|production> --fedhost <url> --anchor <url> [--register]')
  process.exit(2)
}

// Entities the anchor vouches for. rp-suspended is in the content but NOT in this list, on purpose:
// the demo's status-reject case is an entity whose configuration resolves and whose enrolment does not.
const ANCHOR_SUBORDINATES = [
  'as', 'agent', 'api', 'origination-agent', 'payment-agent',
  'tmi', 'as-emea', 'as-partner', 'rp-sales', 'rp-legacy',
]

// ── JOSE, on webcrypto ──────────────────────────────────────────────────────────────────────────
const enc = new TextEncoder()
const b64u = (buf) => Buffer.from(buf).toString('base64url')
const b64uJson = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url')
const unb64uJson = (s) => JSON.parse(Buffer.from(s, 'base64url').toString('utf8'))
const payloadOf = (jwt) => unb64uJson(jwt.split('.')[1])

async function newKey() {
  const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  const pub = await crypto.subtle.exportKey('jwk', kp.publicKey)
  // RFC 7638 thumbprint, as the kid - the same identity the key would have anywhere else.
  const canonical = JSON.stringify({ crv: pub.crv, kty: pub.kty, x: pub.x, y: pub.y })
  const digest = await crypto.subtle.digest('SHA-256', enc.encode(canonical))
  const kid = b64u(new Uint8Array(digest))
  return {
    priv: kp.privateKey,
    jwk: { kty: pub.kty, crv: pub.crv, x: pub.x, y: pub.y, kid },
  }
}

async function sign(key, typ, payload) {
  const head = b64uJson({ alg: 'ES256', kid: key.jwk.kid, typ })
  const body = b64uJson(payload)
  const sig = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' }, key.priv, enc.encode(head + '.' + body))
  return head + '.' + body + '.' + b64u(new Uint8Array(sig))
}

async function verify(jwk, jwt) {
  const [h, p, s] = jwt.split('.')
  const pub = await crypto.subtle.importKey(
    'jwk', { ...jwk, key_ops: ['verify'], ext: true }, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify'])
  return crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pub,
    Buffer.from(s, 'base64url'), enc.encode(h + '.' + p))
}

// ── re-homing ───────────────────────────────────────────────────────────────────────────────────
// Rewrite every URL in a decoded payload. Longest prefix first, so a base URL that is a prefix of
// another does not truncate the longer one halfway.
function rehome(node, rules) {
  if (typeof node === 'string') {
    let s = node
    for (const [from, to] of rules) s = s.split(from).join(to)
    return s
  }
  if (Array.isArray(node)) return node.map((n) => rehome(n, rules))
  if (node && typeof node === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(node)) out[rehome(k, rules)] = rehome(v, rules)
    return out
  }
  return node
}

// ── mint ────────────────────────────────────────────────────────────────────────────────────────
const contentPath = join(HERE, `content.${ENV}.json`)
const content = JSON.parse(readFileSync(contentPath, 'utf8'))

// Derive the OLD bases from the content itself rather than asking for them: the file is the only
// authority on what it currently says, and a mistyped --old-fedhost would half-rewrite it.
const anySub = payloadOf(Object.values(content.entityConfigs)[0]).sub
const OLD_FEDHOST = anySub.slice(0, anySub.indexOf('/e/'))
const oldAnchors = new Set()
for (const jwt of Object.values(content.entityConfigs)) {
  for (const h of payloadOf(jwt).authority_hints || []) {
    if (!h.startsWith(OLD_FEDHOST)) oldAnchors.add(h)
  }
}
// Trust-mark ids are minted under the anchor's URL too, so the anchor rule has to cover them.
const RULES = [...oldAnchors].map((a) => [a, NEW_ANCHOR])
RULES.push([OLD_FEDHOST, NEW_FEDHOST])
RULES.sort((a, b) => b[0].length - a[0].length)

console.log(`re-homing  ${OLD_FEDHOST}  ->  ${NEW_FEDHOST}`)
for (const a of oldAnchors) console.log(`           ${a}  ->  ${NEW_ANCHOR}`)

const now = Math.floor(Date.now() / 1000)
const exp = now + LIFETIME_DAYS * 86400

// One fresh key per entity.
const keys = {}
for (const slug of Object.keys(content.entityConfigs)) keys[slug] = await newKey()

// tmi signs the trust marks, so its key must exist before any configuration that carries one - it
// does, because every key is minted above. The ORDER that matters is only that a trust mark is
// re-signed while minting the configuration that carries it, which is what happens below.
const entityId = (slug) => `${NEW_FEDHOST}/e/${slug}`

const newConfigs = {}
let sigCount = 0
for (const [slug, oldJwt] of Object.entries(content.entityConfigs)) {
  const p = rehome(payloadOf(oldJwt), RULES)
  p.iss = p.sub = entityId(slug)
  p.iat = now
  p.exp = exp
  p.jwks = { keys: [keys[slug].jwk] }

  if (Array.isArray(p.trust_marks)) {
    p.trust_marks = await Promise.all(p.trust_marks.map(async (tm) => {
      const tp = rehome(payloadOf(tm.trust_mark), RULES)
      tp.iss = entityId('tmi')
      tp.sub = entityId(slug)
      tp.iat = now
      tp.exp = exp
      sigCount++
      return { id: rehome(tm.id, RULES), trust_mark: await sign(keys.tmi, 'trust-mark+jwt', tp) }
    }))
  }

  newConfigs[slug] = await sign(keys[slug], 'entity-statement+jwt', p)
  sigCount++
}

// Subordinate statements issued by the fedhost-local authorities (NOT by the anchor).
const newSubStmts = {}
for (const [issSlug, subs] of Object.entries(content.subStmts)) {
  newSubStmts[issSlug] = {}
  for (const [oldSubId, oldJwt] of Object.entries(subs)) {
    const subSlug = oldSubId.slice(oldSubId.lastIndexOf('/') + 1)
    const p = rehome(payloadOf(oldJwt), RULES)
    p.iss = entityId(issSlug)
    p.sub = entityId(subSlug)
    p.iat = now
    p.exp = exp
    p.jwks = { keys: [keys[subSlug].jwk] }
    newSubStmts[issSlug][entityId(subSlug)] = await sign(keys[issSlug], 'entity-statement+jwt', p)
    sigCount++
  }
}

// ── verify before writing ───────────────────────────────────────────────────────────────────────
// A configuration that does not verify under the key it advertises is worse than no configuration:
// it fails at resolve time, in someone else's logs.
let checked = 0
for (const [slug, jwt] of Object.entries(newConfigs)) {
  if (!(await verify(keys[slug].jwk, jwt))) throw new Error(`entity configuration ${slug} does not verify`)
  checked++
  for (const tm of payloadOf(jwt).trust_marks || []) {
    if (!(await verify(keys.tmi.jwk, tm.trust_mark))) throw new Error(`trust mark on ${slug} does not verify under tmi`)
    checked++
  }
}
for (const [issSlug, subs] of Object.entries(newSubStmts)) {
  for (const [subId, jwt] of Object.entries(subs)) {
    if (!(await verify(keys[issSlug].jwk, jwt))) throw new Error(`subordinate statement ${issSlug} -> ${subId} does not verify`)
    checked++
  }
}

writeFileSync(contentPath, JSON.stringify({ entityConfigs: newConfigs, subStmts: newSubStmts }, null, 2) + '\n')
console.log(`minted ${Object.keys(keys).length} keys, ${sigCount} signatures, ${checked} verified -> ${contentPath}`)

// ── enrol at the anchor ─────────────────────────────────────────────────────────────────────────
if (!REGISTER) {
  console.log('not registering (pass --register to enrol the subordinates at the anchor)')
  process.exit(0)
}

const ADMIN = `${NEW_ANCHOR}/api/v1/admin/subordinates`
const existing = await fetch(ADMIN).then((r) => (r.ok ? r.json() : [])).catch(() => [])
for (const slug of ANCHOR_SUBORDINATES) {
  const eid = entityId(slug)
  for (const s of existing) {
    if (s.entity_id === eid) await fetch(`${ADMIN}/${s.id}`, { method: 'DELETE' }).catch(() => {})
  }
  const r = await fetch(ADMIN, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ entity_id: eid, jwks: { keys: [keys[slug].jwk] } }),
  })
  console.log(`  ${r.status}  ${slug}`)
}
console.log(`registered ${ANCHOR_SUBORDINATES.length} subordinates; rp-suspended left unenrolled by design`)
