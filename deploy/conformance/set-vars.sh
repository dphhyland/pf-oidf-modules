#!/usr/bin/env bash
# Apply vars.env - the NON-secret service variables - to the Railway service, without redeploying.
#
# The secrets are set-secrets.sh, by a person. This is everything else, and it is idempotent: run it
# whenever vars.env changes, then `railway up`. Nothing is read from anywhere but vars.env, and the
# values are ordinary configuration (issuer, client ids, switches), so they are echoed as they are set.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${RAILWAY_PROJECT_ID:-3c876ea8-3baa-4173-af59-8c8e1f34a74f}"

args=()
while IFS= read -r line; do
  [[ "$line" =~ ^[A-Z_][A-Z0-9_]*= ]] || continue
  args+=(--set "$line")
  echo "set ${line%%=*}=${line#*=}"
done < "$HERE/vars.env"
[[ ${#args[@]} -gt 0 ]] || { echo "ERROR: nothing to set in vars.env" >&2; exit 1; }
railway variables --project "$PROJECT" --service pingfederate --environment production --skip-deploys "${args[@]}" >/dev/null
echo "done - deploy with: railway up \"\$(PF_AGENTIC_IDENTITY_HOME=... ./compose-context.sh)\" --path-as-root --service pingfederate --no-gitignore --ci"
