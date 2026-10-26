#!/usr/bin/env bash
# Compose the rig PF's `railway up` context in .context/ (git-ignored): pf-agentic-identity's image build
# (build/pingfederate/, with modules/ and assembler/ already staged there by stage-modules.sh) plus this
# directory's railway.json and the ENCRYPTED archive. Only data.zip.age is ever staged: the plaintext
# archive holds pf.jwk and never enters a build context from here.
#
#   ./compose-context.sh <path to data.zip.age>
#   ( cd .context && railway up --ci --no-gitignore -p d1b703dd-487d-4439-869e-023a5b0a8da0 -e production -s pingfederate )
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CAP="${PF_AGENTIC_IDENTITY_HOME:-$HERE/../../../pf-agentic-identity}"
BUILD="$CAP/build/pingfederate"
ARCHIVE="${1:?usage: compose-context.sh <data.zip.age>}"
[[ "$ARCHIVE" == *.age ]] || { echo "ERROR: only an age-encrypted archive (data.zip.age) is staged" >&2; exit 1; }
[[ -f "$BUILD/modules/MANIFEST" ]] || { echo "ERROR: no staged modules in $BUILD/modules: run stage-modules.sh --profile production there" >&2; exit 1; }
grep -q 'profile=production' "$BUILD/modules/MANIFEST" || { echo "ERROR: $BUILD/modules was staged for another profile" >&2; exit 1; }

CTX="$HERE/.context"
rm -rf "$CTX"; mkdir -p "$CTX/overlay"
cp "$BUILD/Dockerfile" "$BUILD/assemble-pf-runtime-war.sh" "$BUILD/filters.xml" "$BUILD/pf-entrypoint.sh" "$BUILD/pf-healthcheck.sh" "$CTX/"
cp -R "$BUILD/modules" "$CTX/modules"
cp -R "$BUILD/assembler" "$CTX/assembler"
cp -R "$BUILD/overlay/config-store" "$CTX/overlay/config-store"
cp "$HERE/railway.json" "$CTX/"
cp "$ARCHIVE" "$CTX/data.zip.age"
echo "composed $CTX: $(head -1 "$CTX/modules/MANIFEST")"
