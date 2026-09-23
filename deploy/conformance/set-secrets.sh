#!/usr/bin/env bash
# Put the rig's three secrets on its Railway service. Run by a person, once, and again after
# gen-keys.sh has rotated secrets.env.
#
# It reads each value from where it already lives and hands it to the Railway CLI on stdin, so none
# of them is printed, none is an argument in a process listing, and none lands in a shell history:
#
#   PING_IDENTITY_DEVOPS_USER, PING_IDENTITY_DEVOPS_KEY   <- ~/.pingidentity/config
#       The image bakes no licence; the base image fetches an evaluation one with these at each boot.
#   OIDF_SSF_INTROSPECTION_CLIENT_SECRET                   <- ./secrets.env
#       What the SSF servlet authenticates to PF's introspection endpoint with. Its other half is
#       already in the archive, as the conformance-ssf-rs client (terraform/clients.tf).
#
# Nothing deploys as a result (skip-deploys); the next `railway up` picks them up.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PING_CONFIG="${PING_DEVOPS_CONFIG:-$HOME/.pingidentity/config}"
PROJECT="${RAILWAY_PROJECT_ID:-3c876ea8-3baa-4173-af59-8c8e1f34a74f}"

value_of() { sed -n "s/^$1=//p" "$2" | head -1; }
put() { # name, value
  [[ -n "$2" ]] || { echo "ERROR: no value found for $1" >&2; exit 1; }
  printf '%s' "$2" | railway variables --project "$PROJECT" --service pingfederate --environment production \
    --skip-deploys --set-from-stdin "$1" >/dev/null
  echo "set $1"
}

[[ -f "$PING_CONFIG" ]] || { echo "ERROR: $PING_CONFIG not found (set PING_DEVOPS_CONFIG)" >&2; exit 1; }
RIG="${PF_AGENTIC_IDENTITY_HOME:?set PF_AGENTIC_IDENTITY_HOME to a pf-agentic-identity checkout}/conformance"
[[ -f "$RIG/secrets.env" ]] || { echo "ERROR: no $RIG/secrets.env - run $RIG/gen-keys.sh first" >&2; exit 1; }

put PING_IDENTITY_DEVOPS_USER "$(value_of PING_IDENTITY_DEVOPS_USER "$PING_CONFIG")"
put PING_IDENTITY_DEVOPS_KEY "$(value_of PING_IDENTITY_DEVOPS_KEY "$PING_CONFIG")"
put OIDF_SSF_INTROSPECTION_CLIENT_SECRET "$(value_of TF_VAR_ssf_introspection_client_secret "$RIG/secrets.env")"
echo "done - values were not displayed"
