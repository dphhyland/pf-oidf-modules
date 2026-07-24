#!/bin/sh
set -eu
mkdir -p /opt/spire/data /tmp/spire-agent/public
sed "s|SERVER_ADDR|${SPIRE_SERVER_ADDRESS}|" /opt/spire/conf/agent/agent.conf.tmpl > /opt/spire/conf/agent/agent.conf
JOIN_TOKEN="${SPIRE_JOIN_TOKEN:-$(cat "${JOIN_TOKEN_FILE:-/dev/null}" 2>/dev/null || true)}"
/opt/spire/bin/spire-agent run -config /opt/spire/conf/agent/agent.conf -joinToken "${JOIN_TOKEN}" &
for i in $(seq 1 30); do [ -S /tmp/spire-agent/public/api.sock ] && break; sleep 1; done
exec /workload.sh
