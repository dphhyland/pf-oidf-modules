#!/usr/bin/env bash
# Contract-test the SSF transmitter.
#
#   harness/probe-ssf.sh <base-url> [bearer-token]
#     e.g. harness/probe-ssf.sh https://reseau.proxy.rlwy.net:38844/oidf $TOKEN
#
# Without a token: checks /.well-known/ssf-configuration advertises the SSF-mandated fields.
# With a bearer token (ssf.manage scope): runs the full receiver flow — create poll stream -> add
# subject -> request verification -> poll it back -> ack -> delete the stream.
set -euo pipefail
BASE="${1:?usage: probe-ssf.sh <base-url> [bearer-token]}"
TOKEN="${2:-${SSF_BEARER:-}}"
BASE="${BASE%/}"
SESSION_EVT="https://schemas.openid.net/secevent/caep/event-type/session-revoked"

echo "== SSF transmitter probe: $BASE =="

cfg="$(curl -sk "$BASE/.well-known/ssf-configuration")"
for field in issuer jwks_uri delivery_methods_supported configuration_endpoint status_endpoint \
             add_subject_endpoint remove_subject_endpoint verification_endpoint; do
  echo "$cfg" | grep -q "\"$field\"" || { echo "[FAIL] ssf-configuration missing \"$field\""; echo "$cfg"; exit 1; }
done
echo "[PASS] ssf-configuration advertises the required fields"

if [[ -z "$TOKEN" ]]; then
  echo "(no bearer token — skipping the authenticated flow; pass one as arg 2 or \$SSF_BEARER)"
  exit 0
fi
command -v jq >/dev/null || { echo "the authenticated flow needs jq"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

stream="$(curl -sk -X POST "$BASE/ssf/streams" "${AUTH[@]}" \
  -d "{\"aud\":\"https://receiver.example.com\",\"delivery\":{\"method\":\"urn:ietf:rfc:8936\"},\"events_requested\":[\"$SESSION_EVT\"]}")"
sid="$(echo "$stream" | jq -r '.stream_id // empty')"
[[ -n "$sid" ]] || { echo "[FAIL] no stream_id — response: $stream"; exit 1; }
echo "[PASS] created poll stream $sid"

curl -sk -X POST "$BASE/ssf/subjects:add" "${AUTH[@]}" \
  -d "{\"stream_id\":\"$sid\",\"subject\":{\"format\":\"email\",\"email\":\"alice@example.com\"}}" \
  -o /dev/null -w "     add-subject   HTTP %{http_code}\n"

curl -sk -X POST "$BASE/ssf/verify" "${AUTH[@]}" \
  -d "{\"stream_id\":\"$sid\",\"state\":\"probe-state\"}" -o /dev/null -w "     verify        HTTP %{http_code}\n"

poll="$(curl -sk -X POST "$BASE/ssf/poll?stream_id=$sid" "${AUTH[@]}" -d '{"maxEvents":10,"returnImmediately":true}')"
jti="$(echo "$poll" | jq -r '.sets | keys[0] // empty')"
[[ -n "$jti" ]] || { echo "[FAIL] verification SET not returned by poll — response: $poll"; exit 1; }
echo "[PASS] polled the verification SET ($jti)"

curl -sk -X POST "$BASE/ssf/poll?stream_id=$sid" "${AUTH[@]}" -d "{\"ack\":[\"$jti\"]}" \
  -o /dev/null -w "     ack           HTTP %{http_code}\n"
curl -sk -X DELETE "$BASE/ssf/streams?stream_id=$sid" "${AUTH[@]}" \
  -o /dev/null -w "     delete-stream HTTP %{http_code}\n"

echo "== SSF probe OK: read-config -> create -> add-subject -> verify -> poll -> ack =="
