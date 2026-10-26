#!/usr/bin/env node
//
// Mint an agent TYPE for the observatory's rig as a self-signed hosted entity at PingFederate.
//
// WHY SELF-SIGNED. PingFederate's hosted entities normally sign with an OpenBao transit key
// (hostingKeyRef). This project's OpenBao was never rebuilt (../README.md), so the type holds its
// own Federation Entity Key: PingFederate registers the public half ("hostingMode": "SELF_SIGNED",
// "federationJwks"), serves the Entity Configuration the type signed and publishes it with
// PUT /federation/agents/<id>/entity-configuration, and its Subordinate Statement vouches for the key
// (pf-agentic-identity docs/federation/operations.md, "Self-signed agents").
//
// WHAT IT WRITES, for one type (agentd-docker or agentd-railway, metadata in <type>.json):
//   <out>/<type>.registration.json      the POST /federation/agents body (public)
//   <out>/<type>.entity-configuration.jwt the signed Entity Configuration (public)
//   --key-out <file>                     the private JWK, mode 600 - a SECRET: GitHub environment
//                                        secret AGENT_TYPE_KEY_<TYPE> of this repo, never committed
//                                        (*.key.json is git-ignored)
// With --key-in the same key signs again (a re-mint when the authority moves or the metadata
// changes); without it a fresh P-256 key is made.
//
// Usage:
//   node mint-type.mjs --authority https://pf.example.com --type agentd-docker \
//        --key-out agentd-docker.key.json [--key-in agentd-docker.key.json] [--days 7] [--out .]
//
// The entity id is <authority>/federation/agents/<type> (built by PingFederate from
// OIDF_AUTHORITY_ENTITY_ID, never from the request's Host), so --authority must be that setting's
// value. The registration and the publication are curl calls in README.md.
import { webcrypto as crypto } from 'node:crypto'
import { chmodSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  if (i < 0) return fallback
  const v = process.argv[i + 1]
  if (v === undefined || v.startsWith('--')) throw new Error(`--${name} needs a value`)
  return v
}

const authority = arg('authority')
const type = arg('type')
const keyOut = arg('key-out')
const keyIn = arg('key-in')
const days = Number(arg('days', '7'))
const out = arg('out', '.')
if (!authority || !type || !keyOut) {
  console.error('usage: mint-type.mjs --authority <url> --type <agentd-docker|agentd-railway> --key-out <file> [--key-in <file>] [--days 7] [--out <dir>]')
  process.exit(2)
}
if (!/^https:\/\/[^/]+$/.test(authority)) throw new Error(`--authority must be an https origin with no path: ${authority}`)
if (!Number.isInteger(days) || days < 1 || days > 7) throw new Error('--days must be a whole number of days, 1 to 7 (PingFederate refuses an Entity Configuration that lives longer than 604800 s)')

// ── JOSE, on webcrypto (as ../../fedhost/remint.mjs does it) ────────────────────────────────────
const enc = new TextEncoder()
const b64u = (buf) => Buffer.from(buf).toString('base64url')
const b64uJson = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url')

async function thumbprint(pub) {
  // RFC 7638: the required members, lexicographic, no whitespace.
  const canonical = JSON.stringify({ crv: pub.crv, kty: pub.kty, x: pub.x, y: pub.y })
  return b64u(new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(canonical))))
}

async function newKey() {
  const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  const priv = await crypto.subtle.exportKey('jwk', kp.privateKey)
  const kid = await thumbprint(priv)
  return { kty: 'EC', crv: 'P-256', x: priv.x, y: priv.y, d: priv.d, kid, alg: 'ES256', use: 'sig' }
}

async function importPrivate(jwk) {
  if (jwk.kty !== 'EC' || jwk.crv !== 'P-256' || !jwk.d) throw new Error('--key-in must be a P-256 private JWK')
  const key = await crypto.subtle.importKey('jwk', { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, d: jwk.d }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
  return { ...jwk, kid: await thumbprint(jwk), alg: 'ES256', use: 'sig', key }
}

async function sign(priv, typ, payload) {
  const head = b64uJson({ alg: 'ES256', kid: priv.kid, typ })
  const body = b64uJson(payload)
  const key = priv.key ?? (await crypto.subtle.importKey('jwk', { kty: priv.kty, crv: priv.crv, x: priv.x, y: priv.y, d: priv.d }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']))
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(`${head}.${body}`))
  return `${head}.${body}.${b64u(new Uint8Array(sig))}`
}

// ── the type ────────────────────────────────────────────────────────────────────────────────────
const spec = JSON.parse(readFileSync(join(HERE, `${type}.json`), 'utf8'))
if (spec.id !== type) throw new Error(`${type}.json names id ${spec.id}`)
const priv = keyIn ? await importPrivate(JSON.parse(readFileSync(keyIn, 'utf8'))) : await newKey()
const pub = { kty: priv.kty, crv: priv.crv, x: priv.x, y: priv.y, kid: priv.kid, alg: 'ES256', use: 'sig' }
const entityId = `${authority}/federation/agents/${type}`
const now = Math.floor(Date.now() / 1000)

// OpenID Federation 1.0 section 3.1: a self-signed Entity Configuration, iss = sub = the entity,
// jwks = its Federation Entity Keys, authority_hints = the authority that vouches for it.
const configuration = await sign(priv, 'entity-statement+jwt', {
  iss: entityId,
  sub: entityId,
  iat: now,
  exp: now + days * 86400,
  jwks: { keys: [pub] },
  metadata: spec.metadata,
  authority_hints: [authority],
})

const registration = {
  id: type,
  hostingMode: 'SELF_SIGNED',
  federationJwks: { keys: [pub] },
  metadata: spec.metadata,
  listable: spec.listable ?? false,
  notAfterSeconds: days * 86400,
}

writeFileSync(join(out, `${type}.registration.json`), JSON.stringify(registration, null, 2) + '\n')
writeFileSync(join(out, `${type}.entity-configuration.jwt`), configuration + '\n')
if (!keyIn || keyIn !== keyOut) {
  writeFileSync(keyOut, JSON.stringify(priv.key ? { kty: priv.kty, crv: priv.crv, x: priv.x, y: priv.y, d: priv.d, kid: priv.kid, alg: 'ES256', use: 'sig' } : priv) + '\n', { mode: 0o600 })
  chmodSync(keyOut, 0o600)
}
console.log(`${type}: entity ${entityId}, kid ${pub.kid}, valid ${days} days`)
console.log(`  registration: ${join(out, `${type}.registration.json`)}`)
console.log(`  entity configuration: ${join(out, `${type}.entity-configuration.jwt`)}`)
console.log(`  private key: ${keyOut} (mode 600; store as a GitHub environment secret, never commit)`)
