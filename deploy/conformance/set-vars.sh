#!/usr/bin/env bash
# Apply the rig's vars.env (pf-agentic-identity/conformance) - the NON-secret service variables - to the
# Railway service, without redeploying.
#
# The secrets are set-secrets.sh, by a person. This is everything else, and it is idempotent: run it
# whenever vars.env changes, then `railway up`. Nothing is read from anywhere but vars.env, and the
# values are ordinary configuration (issuer, client ids, switches), so they are echoed as they are set.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${RAILWAY_PROJECT_ID:-3c876ea8-3baa-4173-af59-8c8e1f34a74f}"
RIG="${PF_AGENTIC_IDENTITY_HOME:?set PF_AGENTIC_IDENTITY_HOME to a pf-agentic-identity checkout}/conformance"
# The rig's vars.env is written for a PF on localhost; the same substitution its up.sh makes, for the
# lines that carry the issuer. The introspection endpoint is NOT one of them: PF asks itself, on
# loopback, and a hostname there would have PF calling out through the proxy to its own certificate.
PF_BASE_URL="${PF_BASE_URL:-https://thomas.proxy.rlwy.net:50715}"

args=()
while IFS= read -r line; do
  [[ "$line" =~ ^[A-Z_][A-Z0-9_]*= ]] || continue
  [[ "$line" =~ ^(OIDF_SSF_ISSUER|OIDF_FEDERATION_[A-Z_]+)= ]] && line="${line//https:\/\/localhost:9031/$PF_BASE_URL}"
  args+=(--set "$line")
  echo "set ${line%%=*}=${line#*=}"
done < "$RIG/vars.env"
[[ ${#args[@]} -gt 0 ]] || { echo "ERROR: nothing to set in vars.env" >&2; exit 1; }
railway variables --project "$PROJECT" --service pingfederate --environment production --skip-deploys "${args[@]}" >/dev/null
echo "done - deploy with: railway up \"\$($RIG/compose-context.sh)\" --path-as-root --service pingfederate --no-gitignore --ci"
