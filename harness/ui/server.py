#!/usr/bin/env python3
"""
Local proxy + static server for the attestation client-auth demo UI.

A browser can't call the PingFederate runtime directly: it sits behind a Railway
TCP proxy with a self-signed cert (CN=localhost) on a non-standard port, and the
servlet sends no CORS headers. This tiny stdlib server (no pip installs) serves
the UI and forwards two calls to PF for it:

  POST /api/challenge  -> {PF_BASE}/federation/attestation-challenge
  POST /api/token      -> {TOKEN_ENDPOINT}   (with the attestation headers)

Usage:
  python3 server.py                       # defaults to the Railway runtime
  PF_BASE=https://host:port/oidf python3 server.py
  python3 server.py 8800                  # listen port (default 8800)

Then open http://localhost:8800
"""
import json
import os
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Default to the LOCAL single-instance PF (admin console + runtime + module together).
# Override with PF_BASE/TOKEN_ENDPOINT/CONSOLE_URL for a different instance.
PF_BASE = os.environ.get("PF_BASE", "https://localhost:19031/oidf").rstrip("/")
CHALLENGE_URL = PF_BASE + "/federation/attestation-challenge"
# PF's OAuth token endpoint is at the runtime ROOT (not under the module's /oidf context).
_origin = urllib.parse.urlsplit(PF_BASE)
PF_ORIGIN = f"{_origin.scheme}://{_origin.netloc}"
TOKEN_ENDPOINT = os.environ.get("TOKEN_ENDPOINT", PF_ORIGIN + "/as/token.oauth2")
# Admin console (for the "Open PingFederate Console" link). Local default.
CONSOLE_URL = os.environ.get("CONSOLE_URL", "https://localhost:19999/pingfederate/app")
# PF mandates a client auth method for client_credentials; the proxy supplies the demo
# client's id+secret so the request passes client auth and reaches the attestation
# issuance criterion (the actual client authentication is done by the attestation hook).
CLIENT_ID = os.environ.get("CLIENT_ID", "https://rp.example.com")
CLIENT_SECRET = os.environ.get("CLIENT_SECRET", "demo-secret-123")
# Workload attributes the demo UI advertises in its attestation's "workload" claim.
# git_commit: $OIDF_GIT_COMMIT (Railway sets no git), else `git rev-parse`, else "unknown".
SOFTWARE_VERSION = os.environ.get("SOFTWARE_VERSION", "0.0.1-SNAPSHOT")


def _git_commit():
    env = os.environ.get("OIDF_GIT_COMMIT")
    if env:
        return env
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                             cwd=HERE, capture_output=True, text=True, timeout=3)
        return out.stdout.strip() or "unknown"
    except Exception:  # noqa: BLE001
        return "unknown"


# Bind: 0.0.0.0 so it works both locally and on Railway (which injects $PORT).
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("PORT", "8800"))

HERE = os.path.dirname(os.path.abspath(__file__))
GIT_COMMIT = _git_commit()
# Accept PF's self-signed cert for this dev/test tool.
SSL_CTX = ssl._create_unverified_context()


def pf_post(url, data=None, headers=None):
    """POST to PF, returning (status, body_text, response_headers)."""
    req = urllib.request.Request(url, data=data, method="POST")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), dict(e.headers)
    except Exception as e:  # noqa: BLE001
        return 0, f"proxy error: {e}", {}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="application/json"):
        b = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):  # quieter
        pass

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), "rb") as f:
                self._send(200, f.read(), "text/html; charset=utf-8")
        elif self.path == "/config":
            self._send(200, json.dumps({
                "pf_base": PF_BASE,
                "challenge_url": CHALLENGE_URL,
                "token_endpoint": TOKEN_ENDPOINT,
                "console_url": CONSOLE_URL,
                "client_id": CLIENT_ID,
                "git_commit": GIT_COMMIT,
                "software_version": SOFTWARE_VERSION,
            }))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        if self.path == "/api/challenge":
            status, body, hdrs = pf_post(CHALLENGE_URL)
            self._send(200, json.dumps({
                "status": status, "body": body,
                "cache_control": hdrs.get("Cache-Control", ""),
                "content_type": hdrs.get("Content-Type", ""),
            }))
        elif self.path == "/api/token":
            payload = json.loads(raw or b"{}")
            form = payload.get("form", {})
            headers = payload.get("headers", {})
            # Supply the demo client's auth so the request reaches the issuance criterion.
            form.setdefault("client_id", CLIENT_ID)
            if CLIENT_SECRET:
                form.setdefault("client_secret", CLIENT_SECRET)
            data = "&".join(f"{urllib.parse.quote(k)}={urllib.parse.quote(v)}"
                            for k, v in form.items()).encode()
            headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
            status, body, hdrs = pf_post(TOKEN_ENDPOINT, data=data, headers=headers)
            self._send(200, json.dumps({"status": status, "body": body}))
        else:
            self._send(404, json.dumps({"error": "not found"}))


class DualStackServer(ThreadingHTTPServer):
    """Bind IPv6 dual-stack so Railway's edge (which routes over IPv6) can reach us."""
    address_family = socket.AF_INET6

    def server_bind(self):
        try:
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        except (AttributeError, OSError):
            pass
        super().server_bind()


if __name__ == "__main__":
    print("Attestation demo UI", flush=True)
    print(f"  PF runtime     : {PF_BASE}", flush=True)
    print(f"  token endpoint : {TOKEN_ENDPOINT}", flush=True)
    print(f"  listening on   : [::]:{PORT}", flush=True)
    DualStackServer(("::", PORT), Handler).serve_forever()
