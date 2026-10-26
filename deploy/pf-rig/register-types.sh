#!/usr/bin/env bash
# Register and publish the rig's agent types on pf-rig, from the keys kept on the service's Railway variables.
# Run after every PF restart (the authority is in memory) and weekly (an Entity Configuration lives 7 days).
# Keys and the operator token are read with the Railway CLI into a private temp dir, used, and removed; nothing
# is printed but status codes.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT=d1b703dd-487d-4439-869e-023a5b0a8da0 ENVIRONMENT=production SERVICE=pingfederate
PF="${PF:-https://pingfederate-production.up.railway.app}"
TMP=$(mktemp -d); chmod 700 "$TMP"; trap 'rm -rf "$TMP"' EXIT
railway variable list -p "$PROJECT" -e "$ENVIRONMENT" -s "$SERVICE" --json 2>/dev/null > "$TMP/vars.json"
var() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]], end='')" "$TMP/vars.json" "$1"; }
TOKEN=$(var OIDF_AUTHORITY_ADMIN_TOKEN)
for t in agentd-docker agentd-railway; do
  key="AGENT_TYPE_KEY_$(echo "$t" | tr 'a-z-' 'A-Z_')"
  var "$key" > "$TMP/$t.key.json"
  node "$HERE/../pingfederate/agents/mint-type.mjs" --authority "$PF" --type "$t" --key-in "$TMP/$t.key.json" --key-out "$TMP/$t.key.json" --out "$TMP" >/dev/null
  reg=$(curl -s -o /dev/null -w '%{http_code}' -m 30 "$PF/federation/agents" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data @"$TMP/$t.registration.json")
  pub=$(curl -s -o /dev/null -w '%{http_code}' -m 30 -X PUT "$PF/federation/agents/$t/entity-configuration" -H 'Content-Type: application/entity-statement+jwt' --data-binary @"$TMP/$t.entity-configuration.jwt")
  res=$(curl -s -o /dev/null -w '%{http_code}' -m 30 "$PF/federation/resolve?sub=$PF/federation/agents/$t&trust_anchor=$PF")
  echo "$t: register $reg (201 new, 409 already there), publish $pub (204), resolve $res (200)"
done
