FROM ghcr.io/spiffe/spire-server:1.11.2 AS spire
FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=spire /opt/spire/bin/spire-server /opt/spire/bin/spire-server
COPY server.conf /opt/spire/conf/server/server.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 8081 8080
ENTRYPOINT ["/entrypoint.sh"]
