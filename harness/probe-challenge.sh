#!/usr/bin/env bash
# Contract test for the attestation challenge endpoint on a DEPLOYED instance.
#
#   harness/probe-challenge.sh [baseUrl]
#   default baseUrl: https://reseau.proxy.rlwy.net:17055/oidf  (the Railway runtime)
#
# Asserts: HTTP 200, application/json, Cache-Control: no-store, an
# attestation_challenge field, and that two calls return distinct challenges.
set -euo pipefail
BASE="${1:-https://reseau.proxy.rlwy.net:17055/oidf}"
URL="${BASE%/}/federation/attestation-challenge"
echo "probing: $URL"

hdrs="$(mktemp)"; body="$(curl -sk -X POST "$URL" -D "$hdrs" -w '%{http_code}' --max-time 25)"
code="${body: -3}"; json="${body%???}"
fail=0
check() { if eval "$2"; then echo "  [PASS] $1"; else echo "  [FAIL] $1"; fail=1; fi; }

check "HTTP 200"                 '[ "$code" = "200" ]'
check "Content-Type json"        'grep -qi "content-type:.*application/json" "$hdrs"'
check "Cache-Control: no-store"  'grep -qi "cache-control:.*no-store" "$hdrs"'
check "has attestation_challenge" 'echo "$json" | grep -q "attestation_challenge"'

c1="$(echo "$json" | sed -E 's/.*"attestation_challenge":"([^"]+)".*/\1/')"
c2="$(curl -sk -X POST "$URL" --max-time 25 | sed -E 's/.*"attestation_challenge":"([^"]+)".*/\1/')"
check "challenges are unique"    '[ -n "$c1" ] && [ "$c1" != "$c2" ]'

rm -f "$hdrs"
echo "challenge sample: $c1"
exit $fail
