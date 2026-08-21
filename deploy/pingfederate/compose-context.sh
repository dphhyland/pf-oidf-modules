#!/usr/bin/env bash
# Compose the pingfederate-runtime build context.
#
# The PF image build (Dockerfile, assemble-pf-runtime-war.sh, the config-store overlay) lives in
# pf-agentic-identity under build/pingfederate/, because three repos consume it and one of them
# pushes the resulting image to ECR - it is not a Railway artefact. THIS repo owns the deployment:
# railway.json, the per-env vars, the demo attester trust, and the config archive plus its keys.
#
# `railway up` needs all of that in one directory, so this assembles .context/ (gitignored) and
# prints its path. Nothing here is authored; delete .context/ freely.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAP="${PF_AGENTIC_IDENTITY_HOME:-$(cd "$HERE/../../.." && pwd)/pf-agentic-identity}"
BUILD="$CAP/build/pingfederate"

[[ -d "$BUILD" ]] || {
  echo "ERROR: no PF image build at $BUILD" >&2
  echo "       Clone pf-agentic-identity beside this repo, or set PF_AGENTIC_IDENTITY_HOME." >&2
  exit 1; }

# The module jars are built there, not here. Stage them unless the caller already did.
if [[ ! -f "$BUILD/modules/MANIFEST" ]]; then
  echo "staging modules from $CAP (no MANIFEST yet)"
  ( cd "$CAP" && mvn -q -DskipTests package )
  "$BUILD/stage-modules.sh"
fi

CTX="$HERE/.context"
rm -rf "$CTX"; mkdir -p "$CTX/overlay"

# From the capability repo: the image build itself.
cp "$BUILD/Dockerfile" "$BUILD/assemble-pf-runtime-war.sh" "$BUILD/pf-entrypoint.sh" "$CTX/"
cp -R "$BUILD/modules" "$CTX/modules"
cp -R "$BUILD/overlay/config-store" "$CTX/overlay/config-store"

# From here: the deployment, the demo trust, and the secrets.
cp "$HERE/railway.json" "$HERE/.railwayignore" "$HERE/oidf-mock-attesters.json" "$CTX/"
# The archive: encrypted (preferred) or plaintext (transitional, pre-rotation).
if [[ -f "$HERE/data.zip.age" ]]; then
  cp "$HERE/data.zip.age" "$CTX/data.zip.age"
  echo "staged data.zip.age (encrypted); PF_ARCHIVE_AGE_KEY must be set on the service"
elif [[ -f "$HERE/data.zip" ]]; then
  cp "$HERE/data.zip" "$CTX/data.zip"
  echo "WARNING: staging a PLAINTEXT data.zip - the master key ends up in an image layer."
  echo "WARNING: encrypt it (age -r \"\$(cat age-recipients/<env>.txt)\" -o data.zip.age data.zip) once the key is rotated."
  # Only the plaintext path needs these staged; the encrypted one takes them from inside the archive.
  for f in overlay/pf.jwk overlay/pingfederate-system-keys.xml; do
    [[ -f "$HERE/$f" ]] || { echo "ERROR: missing $f - stage it in $HERE first (it is a secret, git-ignored)" >&2; exit 1; }
    cp "$HERE/$f" "$CTX/$f"
  done
else
  echo "ERROR: no config archive - stage data.zip.age (preferred) or data.zip in $HERE" >&2; exit 1
fi

echo "composed $CTX ($(ls "$CTX/modules"/*.jar | wc -l | tr -d ' ') module jars)"
echo "$CTX"
