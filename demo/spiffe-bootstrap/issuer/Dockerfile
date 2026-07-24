# The issuance service = the REAL AttestationIssuanceServlet, hosted by the demo harness in serve mode.
# Built from staged jars (see stage.sh) — including the PingFederate SDK jar from YOUR ~/.m2, which the
# servlet class references. Nothing licensed is committed; stage/ is git-ignored and populated at build.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY stage/ /app/stage/
COPY BootstrapHttpHarness.java /app/
RUN javac -cp "$(cat stage/classpath)" -d /app/out /app/BootstrapHttpHarness.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out /app/out
COPY --from=build /app/stage /app/stage
ENV SERVE_PORT=9031
# serve mode: /federation/attestation (servlet) + /as/token.oauth2 (verifier) + /dev/svid + /spire/jwks.json
ENTRYPOINT ["/bin/sh","-c","java -cp \"$(cat stage/classpath):out\" com.pingidentity.ps.oidf.servlet.attestation.BootstrapHttpHarness serve ${SERVE_PORT}"]
