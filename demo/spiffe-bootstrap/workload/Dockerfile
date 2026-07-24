FROM ghcr.io/spiffe/spire-agent:1.11.2 AS spire
FROM python:3.12-slim
RUN pip install --no-cache-dir "pyjwt[crypto]" cryptography
COPY --from=spire /opt/spire/bin/spire-agent /usr/local/bin/spire-agent
COPY bootstrap.py /app/bootstrap.py
WORKDIR /app
# fetch SVID from the mounted agent socket → bootstrap → token; then idle so logs stay readable
ENTRYPOINT ["/bin/sh","-c","python3 bootstrap.py; echo '--- workload idle (bootstrap done) ---'; tail -f /dev/null"]
