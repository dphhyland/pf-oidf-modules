#!/bin/sh
# The rig's boot shim, in front of pf-agentic-identity's /opt/pf-entrypoint.sh. PingFederate reads its bridge
# signing keys from a FILE (OIDF_BRIDGE_SIGNING_KEYS); Railway holds secrets as variables. So: write
# OIDF_BRIDGE_SIGNING_KEYS_JSON to that path (0600, this user only), drop the variable from the environment
# PingFederate inherits, and hand over. Nothing is echoed.
set -eu
if [ -n "${OIDF_BRIDGE_SIGNING_KEYS_JSON:-}" ]; then
  : "${OIDF_BRIDGE_SIGNING_KEYS:?OIDF_BRIDGE_SIGNING_KEYS_JSON is set but OIDF_BRIDGE_SIGNING_KEYS names no path}"
  dir=$(dirname "$OIDF_BRIDGE_SIGNING_KEYS")
  mkdir -p "$dir" && chmod 0700 "$dir"
  umask 077
  printf '%s' "$OIDF_BRIDGE_SIGNING_KEYS_JSON" > "$OIDF_BRIDGE_SIGNING_KEYS"
  unset OIDF_BRIDGE_SIGNING_KEYS_JSON
  echo "rig-entrypoint: bridge signing keys written to $OIDF_BRIDGE_SIGNING_KEYS"
fi
exec /opt/pf-entrypoint.sh "$@"
