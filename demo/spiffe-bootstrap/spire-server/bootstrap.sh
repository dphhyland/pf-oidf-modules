#!/bin/sh
# One-shot SPIRE registration: create the workload entry + a node join token (for the agent).
set -eu
SOCK=/tmp/spire-server/private/api.sock
for i in $(seq 1 30); do [ -S "$SOCK" ] && break; sleep 1; done
BIN=/opt/spire/bin/spire-server

# Idempotent: create the workload registration entry (selector-matched) unless it already exists.
$BIN entry show -socketPath "$SOCK" -spiffeID "$SPIFFE_ID" | grep -q "$SPIFFE_ID" || \
  $BIN entry create -socketPath "$SOCK" \
    -parentID "$PARENT_ID" -spiffeID "$SPIFFE_ID" -selector "$SELECTOR"

# Fresh single-use join token for the node (the agent presents it to attest).
TOKEN=$($BIN token generate -socketPath "$SOCK" -spiffeID "$PARENT_ID" | sed -n 's/^Token: //p')
printf '%s' "$TOKEN" > /shared/join_token
echo "bootstrap: entry ok; join token written to /shared/join_token"
