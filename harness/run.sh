#!/usr/bin/env bash
# Compile and run the attestation flow harness.
#
#   harness/run.sh selfverify
#   harness/run.sh live https://reseau.proxy.rlwy.net:17055/oidf
#   harness/run.sh live <baseUrl> <tokenEndpoint> <clientId>
#
# Resolves the jose4j jar from ~/.m2 (or $JOSE4J_JAR) and the built module jar
# from target/ (or $MODULE_JAR). `selfverify` needs the module jar; `live` does not.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

JOSE4J_JAR="${JOSE4J_JAR:-$(find "$HOME/.m2/repository/org/bitbucket/b_c/jose4j" -name 'jose4j-*.jar' 2>/dev/null | sort -V | tail -1)}"
if [[ -z "${JOSE4J_JAR:-}" || ! -f "$JOSE4J_JAR" ]]; then
  echo "ERROR: jose4j jar not found. Run 'mvn dependency:resolve' or set JOSE4J_JAR=/path/to/jose4j.jar" >&2
  exit 1
fi

MODULE_JAR="${MODULE_JAR:-$(ls "$ROOT"/target/pf-oidf-modules-*.jar 2>/dev/null | head -1 || true)}"

# Runtime deps: commons-logging (module core) + slf4j-api (jose4j). Needed by selfverify.
COMMONS_LOGGING_JAR="${COMMONS_LOGGING_JAR:-$(find "$HOME/.m2/repository/commons-logging/commons-logging" -name 'commons-logging-*.jar' 2>/dev/null | grep -v sources | sort -V | tail -1)}"
SLF4J_JAR="${SLF4J_JAR:-$(find "$HOME/.m2/repository/org/slf4j/slf4j-api" -name 'slf4j-api-*.jar' 2>/dev/null | grep -v sources | sort -V | tail -1)}"

CP="$JOSE4J_JAR"
[[ -n "${MODULE_JAR:-}" && -f "${MODULE_JAR:-}" ]] && CP="$CP:$MODULE_JAR"
[[ -n "${COMMONS_LOGGING_JAR:-}" && -f "${COMMONS_LOGGING_JAR:-}" ]] && CP="$CP:$COMMONS_LOGGING_JAR"
[[ -n "${SLF4J_JAR:-}" && -f "${SLF4J_JAR:-}" ]] && CP="$CP:$SLF4J_JAR"
# jackson (module core JSON). Add whatever is in ~/.m2.
for a in core/jackson-core core/jackson-databind core/jackson-annotations; do
  j="$(find "$HOME/.m2/repository/com/fasterxml/jackson/$a" -name "$(basename "$a")-*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1)"
  [[ -n "$j" && -f "$j" ]] && CP="$CP:$j"
done

OUT="$HERE/out"; mkdir -p "$OUT"

# SSF in-process self-verify (mint + verify a CAEP SET with the module's real SetMinter). Needs the jar.
if [[ "${1:-}" == "ssf-selfverify" ]]; then
  if [[ -z "${MODULE_JAR:-}" || ! -f "${MODULE_JAR:-}" ]]; then
    echo "ERROR: ssf-selfverify needs the built module jar. Run 'mvn -Dassembly.skipAssembly=true package' first," >&2
    echo "       or set MODULE_JAR=/path/to/pf-oidf-modules-0.0.1-SNAPSHOT.jar" >&2
    exit 1
  fi
  javac -cp "$CP" -d "$OUT" "$HERE/SsfSelfVerify.java"
  exec java -cp "$OUT:$CP" SsfSelfVerify
fi

javac -cp "$CP" -d "$OUT" "$HERE/AttestationFlowHarness.java"

if [[ "${1:-selfverify}" == "selfverify" && ( -z "${MODULE_JAR:-}" || ! -f "${MODULE_JAR:-}" ) ]]; then
  echo "ERROR: selfverify needs the built module jar. Run 'mvn -Dassembly.skipAssembly=true package' first," >&2
  echo "       or set MODULE_JAR=/path/to/pf-oidf-modules-0.0.1-SNAPSHOT.jar" >&2
  exit 1
fi

exec java -cp "$OUT:$CP" AttestationFlowHarness "$@"
