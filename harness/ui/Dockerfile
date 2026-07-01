# Demo UI: a tiny stdlib Python server that serves the page and proxies
# /api/challenge + /api/token to PingFederate. Reads $PORT (Railway injects it)
# and binds 0.0.0.0. Point it at a PF runtime with PF_BASE / TOKEN_ENDPOINT.
FROM python:3.12-slim
WORKDIR /app
COPY server.py index.html ./
CMD ["python3", "server.py"]
