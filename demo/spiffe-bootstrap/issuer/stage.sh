#!/usr/bin/env bash
# Stage the jars the issuer image needs (module jar + provided PF SDK + jose4j/jackson/slf4j) into
# issuer/stage/, and the harness source next to the Dockerfile. Run before `docker compose build`.
# The PF SDK jars come from your local ~/.m2 (extracted from the pingidentity/pingfederate image) — they
# are NOT redistributable, so this stays out of git.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd ../../.. && pwd)"; M2="$HOME/.m2/repository"
[ -f "$REPO_ROOT/target/pf-oidf-modules-0.0.1-SNAPSHOT.jar" ] || ( cd "$REPO_ROOT" && mvn -q -DskipTests package )
rm -rf stage && mkdir stage
cp "$REPO_ROOT/target/pf-oidf-modules-0.0.1-SNAPSHOT.jar" stage/
for j in \
  "$M2/org/bitbucket/b_c/jose4j/0.9.6/jose4j-0.9.6.jar" \
  "$(find "$M2" -name 'jackson-databind-2.1*.jar' | sort -V | tail -1)" \
  "$(find "$M2" -name 'jackson-core-2.1*.jar' | sort -V | tail -1)" \
  "$(find "$M2" -name 'jackson-annotations-2.1*.jar' | sort -V | tail -1)" \
  "$(find "$M2" -name 'javax.servlet-api-4.0.0.jar' | head -1)" \
  "$M2/pingfederate/pf-protocolengine/13.0.0.3/pf-protocolengine-13.0.0.3.jar" \
  "$M2/com/pingidentity/pingfederate/pingfederate-sdk/13.0.0.3/pingfederate-sdk-13.0.0.3.jar" \
  "$(find "$M2" -name 'commons-logging-1.2.jar' | head -1)" \
  "$(find "$M2" -name 'slf4j-api-*.jar' | sort -V | tail -1)"; do
  cp "$j" stage/
done
( cd stage && printf '%s' "$(ls *.jar | sed 's#^#stage/#' | paste -sd: -)" > classpath )
cp ../harness/BootstrapHttpHarness.java .
echo "staged $(ls stage/*.jar | wc -l | tr -d ' ') jars → issuer/stage/ + harness source"
