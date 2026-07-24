#!/usr/bin/env bash
# Run the SPIFFE -> Client Attestation bootstrap locally WITHOUT Docker.
#
# Two modes:
#   ./run-local.sh            self-contained: the Java harness serves the real AttestationIssuanceServlet
#                             AND drives a workload through it in one process (fastest smoke test).
#   ./run-local.sh workload   start the harness as a server, then run the REAL Python workload against it
#                             (proves ../workload/bootstrap.py too). Needs python3 + PyJWT + cryptography.
#
# Neither needs SPIRE or PingFederate — it exercises the same servlet + workload code the compose runs,
# so it verifies the bootstrap contract on any machine with a JDK.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd ../.. && pwd)"
HARNESS=harness/BootstrapHttpHarness.java
OUT=/tmp/spiffe-bootstrap-harness

# --- assemble the classpath: monolith jar + provided PF jars + jose4j/jackson/slf4j ---------------
M2="$HOME/.m2/repository"
JAR="$REPO_ROOT/target/pf-oidf-modules-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "Building the module jar (mvn -q -DskipTests package in $REPO_ROOT)…"
  ( cd "$REPO_ROOT" && mvn -q -DskipTests package )
fi
CP="$JAR"
add() { [ -f "$1" ] && CP="$CP:$1" || { echo "missing dep: $1" >&2; exit 1; }; }
add "$M2/org/bitbucket/b_c/jose4j/0.9.6/jose4j-0.9.6.jar"
add "$(find "$M2" -name 'jackson-databind-2.1*.jar' | sort -V | tail -1)"
add "$(find "$M2" -name 'jackson-core-2.1*.jar' | sort -V | tail -1)"
add "$(find "$M2" -name 'jackson-annotations-2.1*.jar' | sort -V | tail -1)"
add "$(find "$M2" -name 'javax.servlet-api-4.0.0.jar' | head -1)"
add "$M2/pingfederate/pf-protocolengine/13.0.0.3/pf-protocolengine-13.0.0.3.jar"
add "$M2/com/pingidentity/pingfederate/pingfederate-sdk/13.0.0.3/pingfederate-sdk-13.0.0.3.jar"
add "$(find "$M2" -name 'commons-logging-1.2.jar' | head -1)"
add "$(find "$M2" -name 'slf4j-api-*.jar' | sort -V | tail -1)"

rm -rf "$OUT" && mkdir -p "$OUT"
javac -cp "$CP" -d "$OUT" "$HARNESS"
MAIN=com.pingidentity.ps.oidf.servlet.attestation.BootstrapHttpHarness

if [ "${1:-}" = "workload" ]; then
  echo "Starting the issuance harness on :9031…"
  java -cp "$CP:$OUT" "$MAIN" serve 9031 & HPID=$!
  trap 'kill $HPID 2>/dev/null || true' EXIT
  sleep 3
  echo "Running the Python workload against it…"
  USE_DEV_SVID=1 PF_BASE=http://localhost:9031 ISSUER_BASE=http://localhost:9031 \
    TOKEN_ENDPOINT=http://localhost:9031/as/token.oauth2 \
    python3 workload/bootstrap.py
else
  java -cp "$CP:$OUT" "$MAIN"
fi
