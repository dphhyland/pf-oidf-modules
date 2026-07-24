#!/bin/sh
echo "=== workload: fetch JWT-SVID via the Workload API ==="
/opt/spire/bin/spire-agent api fetch jwt -audience "https://as.banking.demo" -socketPath /tmp/spire-agent/public/api.sock 2>&1 || echo "fetch failed"
tail -f /dev/null
