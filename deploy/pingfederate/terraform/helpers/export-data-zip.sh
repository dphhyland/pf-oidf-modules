#!/usr/bin/env bash
# Export the running PF's config archive into the per-environment deploy artifact
# ../data.<environment>.zip. NEVER COMMIT THE RESULT. A PF configArchive is a plain zip that contains
# pf.jwk (the master key), pingfederate-system-keys.xml, both keystores, the admin password hash and
# master-key-reversible client secrets — PF obfuscates individual VALUES with the master key and then
# ships the key alongside them, so "encrypted, therefore safe to commit" (what this header used to
# claim) is false. Everything data*.zip is git-ignored and CI fails if one is ever tracked.
# Also refreshes ../data.zip so a local `railway up` from deploy/pingfederate bakes the same thing.
# Run AFTER `terraform apply`, so the archive reflects the Terraform-authored config — including the
# server-settings base URL for THIS environment. Credentialed (your password).
#
#   export TF_VAR_pf_admin_password='…'
#   export TF_VAR_pf_admin_host='https://<admin-tcp-proxy-host:port>'   # the SAME env's PF admin
#   export TF_VAR_environment=staging          # or production (default: staging)
#   ./helpers/export-data-zip.sh
#
# Then commit the .tf changes ONLY, and redeploy. NEVER `git add` the archive: it contains pf.jwk, so
# committing one publishes the master key. That instruction used to sit on this line and is how
# data.staging.zip reached a public remote.
#
# If age-recipients/<environment>.txt exists, this encrypts the archive to it and shreds the plaintext,
# so what remains on disk is safe. age is NOT required on your machine - it runs in a container.
set -euo pipefail
HOST="${TF_VAR_pf_admin_host:?set TF_VAR_pf_admin_host}"
PW="${TF_VAR_pf_admin_password:?set TF_VAR_pf_admin_password}"
case "${TF_VAR_pf_admin_host:-}" in
  https://localhost:*|https://127.0.0.1:*|https://localhost|https://127.0.0.1) ;;
  *) echo "TF_VAR_pf_admin_host must be a local tunnel endpoint (https://localhost:PORT); the PF admin" >&2
     echo "console is deliberately not internet-facing. Open a tunnel with 'railway ssh' - see" >&2
     echo "terraform/variables.tf. Got: '${TF_VAR_pf_admin_host:-<unset>}'" >&2
     exit 1 ;;
esac
USER="${TF_VAR_pf_admin_username:?set TF_VAR_pf_admin_username (no default - name the admin account)}"
ENVIRONMENT="${TF_VAR_environment:-staging}"
case "$ENVIRONMENT" in staging|production) ;; *) echo "TF_VAR_environment must be staging|production (got '$ENVIRONMENT')"; exit 2;; esac
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$DIR/data.$ENVIRONMENT.zip"

curl -sk -u "$USER:$PW" -H 'X-XSRF-Header: PingFederate' \
  -o "$OUT" "$HOST/pf-admin-api/v1/configArchive/export"

if command -v unzip >/dev/null && unzip -tq "$OUT" >/dev/null 2>&1; then
  echo "exported $(wc -c < "$OUT") bytes -> $OUT ($(unzip -l "$OUT" | tail -1 | awk '{print $2}') files)"
else
  echo "WARNING: exported $OUT is not a valid zip — check the admin host/credentials"; rm -f "$OUT"; exit 1
fi
cp "$OUT" "$DIR/data.zip"
echo "copied -> $DIR/data.zip (git-ignored local build input)"

# Sanity: the base URL PF will advertise from this archive. Anything *.elb.amazonaws.com here means the
# server-settings apply didn't land (wrong host? plan not applied?) — don't ship it.
if command -v unzip >/dev/null; then
  BASE="$(unzip -p "$OUT" sourceid-saml2-local-metadata.xml 2>/dev/null | grep -o 'BaseURL="[^"]*"' | head -1)"
  echo "server-settings ${BASE:-BaseURL not found}"
  case "$BASE" in *elb.amazonaws.com*) echo "ERROR: archive still carries the stale ELB base URL"; exit 1;; esac
fi

# ── encrypt to the environment's age recipient ────────────────────────────────────────────────────
# The plaintext archive is a secret with a short useful life: it exists between the export above and
# the encryption here, and then should not exist at all. age runs in a container deliberately - it is
# not a dependency anyone has to install to operate this, and the PF image already carries it
# (alpine 3.23.4, community repo, age 1.2.1 - verified).
PF_IMAGE="${PF_IMAGE:-pingidentity/pingfederate:13.0.3-alpine_3.23.4-al21-latest}"
RECIPIENT_FILE="$DIR/age-recipients/$ENVIRONMENT.txt"

if [ -f "$RECIPIENT_FILE" ]; then
  command -v docker >/dev/null || { echo "ERROR: docker is needed to encrypt the archive (age runs in a container)" >&2; exit 1; }
  RECIPIENT="$(tr -d '[:space:]' < "$RECIPIENT_FILE")"
  case "$RECIPIENT" in age1*) ;; *) echo "ERROR: $RECIPIENT_FILE does not hold an age1... recipient" >&2; exit 1;; esac

  docker run --rm --user root -v "$DIR:/w" -e RCPT="$RECIPIENT" -e ENVNAME="$ENVIRONMENT" \
      --entrypoint sh "$PF_IMAGE" -c '
        apk add --no-cache age >/dev/null 2>&1 || { echo "could not install age in the container" >&2; exit 1; }
        age --encrypt --recipient "$RCPT" --output "/w/data.$ENVNAME.zip.age" "/w/data.$ENVNAME.zip"
      ' || { echo "ERROR: encryption failed; the plaintext archive is still on disk" >&2; exit 1; }

  echo "encrypted -> $DIR/data.$ENVIRONMENT.zip.age ($(wc -c < "$DIR/data.$ENVIRONMENT.zip.age") bytes)"

  # The plaintext has served its purpose. Both copies go - the per-env one and the build-input one.
  for f in "$OUT" "$DIR/data.zip"; do
    [ -f "$f" ] || continue
    if command -v shred >/dev/null; then shred -u "$f"; else rm -P "$f" 2>/dev/null || rm -f "$f"; fi
  done
  echo "plaintext archives removed; data.$ENVIRONMENT.zip.age is what the image build consumes"
  echo "the service needs PF_ARCHIVE_AGE_KEY set to the matching identity"
else
  echo
  echo "WARNING: no $RECIPIENT_FILE, so the archive is still PLAINTEXT on disk - and it contains pf.jwk."
  echo "WARNING: create the recipient (see age-recipients/README.md) and re-run; until then, do not"
  echo "WARNING: commit it, do not attach it to anything, and delete it when you are done."
fi
