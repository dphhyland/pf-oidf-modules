#!/bin/sh
set -eu
mkdir -p /opt/spire/data /www
# Publish the trust bundle (JWKS incl. jwt authorities) for the AS to verify JWT-SVIDs.
( while true; do
    /opt/spire/bin/spire-server bundle show -format spiffe > /www/jwks.json.tmp 2>/dev/null && mv /www/jwks.json.tmp /www/jwks.json || true
    sleep 20
  done ) &
busybox httpd -f -p 8080 -h /www &
exec /opt/spire/bin/spire-server run -config /opt/spire/conf/server/server.conf
