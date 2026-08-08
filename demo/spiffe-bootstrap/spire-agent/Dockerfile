FROM ghcr.io/spiffe/spire-agent:1.11.2 AS spire
FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=spire /opt/spire/bin/spire-agent /opt/spire/bin/spire-agent
COPY agent.conf.tmpl /opt/spire/conf/agent/agent.conf.tmpl
COPY entrypoint.sh /entrypoint.sh
COPY workload.sh /workload.sh
RUN chmod +x /entrypoint.sh /workload.sh
ENTRYPOINT ["/entrypoint.sh"]
