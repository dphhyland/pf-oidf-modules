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
import base64
import json
import os
import re
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
_origin = urllib.parse.urlsplit(PF_BASE)
PF_ORIGIN = f"{_origin.scheme}://{_origin.netloc}"
# Read-only list of clients the module registered into PingFederate (explicit + automatic). The
# RegisteredClientsServlet is baked into pf-runtime.war at the ROOT context (like /as/token.oauth2),
# NOT under the module's /oidf context — so build it from the origin, not PF_BASE (which carries /oidf).
PF_CLIENTS_URL = os.environ.get("PF_CLIENTS_URL", PF_ORIGIN + "/federation/registered-clients")
# PF's OAuth token endpoint is at the runtime ROOT (not under the module's /oidf context).
TOKEN_ENDPOINT = os.environ.get("TOKEN_ENDPOINT", PF_ORIGIN + "/as/token.oauth2")
# The `aud` a private_key_jwt client assertion must carry. PingFederate's native private_key_jwt validator
# checks aud against PF's *configured* runtime base URL (not the request host / external proxy), so behind a
# TCP proxy this differs from TOKEN_ENDPOINT. Override with PF_TOKEN_AUD (e.g. https://localhost:9031/as/token.oauth2).
PF_TOKEN_AUD = os.environ.get("PF_TOKEN_AUD", TOKEN_ENDPOINT)
# Admin console (for the "Open PingFederate Console" link). Local default.
CONSOLE_URL = os.environ.get("CONSOLE_URL", "https://localhost:19999/pingfederate/app")
# Live PF activity logs (optional): pull pingfederate-runtime's emitted logs via Railway's GraphQL API so
# the demo can show the federation-plugin resolve/fetch/registration + assertion-servlet lines. Needs a
# Railway PROJECT token in RAILWAY_TOKEN; without it the activity panel just prints setup instructions.
RAILWAY_TOKEN = os.environ.get("RAILWAY_TOKEN", "")
RAILWAY_API = os.environ.get("RAILWAY_API", "https://backboard.railway.com/graphql/v2")
PF_SERVICE_ID = os.environ.get("PF_SERVICE_ID", "413fdf8a-ce01-45fc-9009-c2a16df48311")        # pingfederate-runtime
PF_ENVIRONMENT_ID = os.environ.get("PF_ENVIRONMENT_ID", "db9ebc2a-b223-42ec-8a15-a26ee83ad24d")  # staging
PF_LOG_FILTER = os.environ.get(
    "PF_LOG_FILTER",
    "RegistrationService|TrustChainValidator|TrustController|OIDFederation|ClientAttestation|"
    "ClientPrivateKeyJwt|AutoRegistration|Automatically registered")
# PF mandates a client auth method for client_credentials; the proxy supplies the demo
# client's id+secret so the request passes client auth and reaches the attestation
# issuance criterion (the actual client authentication is done by the attestation hook).
CLIENT_ID = os.environ.get("CLIENT_ID", "https://rp.example.com")
CLIENT_SECRET = os.environ.get("CLIENT_SECRET", "demo-secret-123")
# Workload attributes the demo UI advertises in its attestation's "workload" claim.
# git_commit: $OIDF_GIT_COMMIT (Railway sets no git), else `git rev-parse`, else "unknown".
SOFTWARE_VERSION = os.environ.get("SOFTWARE_VERSION", "0.0.1-SNAPSHOT")
# Live federation the demo resolves the target AS against (trust-controller anchor + fedhost entities).
# (env keys keep the LIGHTHOUSE / RAILWAY_SERVICE_LIGHTHOUSE_URL names — that is the Railway service id.)
# Prefer an explicit override, else Railway's injected service URL (bare host), else a default.
def _fed_url(explicit_env, railway_ref_env, default):
    v = os.environ.get(explicit_env)
    if v:
        return v.rstrip("/")
    host = os.environ.get(railway_ref_env)
    if host:
        return ("https://" + host).rstrip("/")
    return default


TRUST_CONTROLLER = _fed_url("LIGHTHOUSE", "RAILWAY_SERVICE_LIGHTHOUSE_URL", "https://lighthouse-staging-e017.up.railway.app")
FEDHOST = _fed_url("FEDHOST", "RAILWAY_SERVICE_FEDHOST_URL", "https://fedhost-staging.up.railway.app")
# Deployed agent workload (SPIFFE-attested): the demo watches it come up + remotely invokes its token exchange.
WORKLOAD_URL = os.environ.get("WORKLOAD_URL", "https://agent-workload-production.up.railway.app").rstrip("/")
# The step-3 "target AS federation profile" options map to real federation entities.
AS_PROFILE_SUB = {
    "home-emea":   FEDHOST + "/e/as-emea",      # under the trust controller + workload-inspection mark
    "partner-crm": FEDHOST + "/e/as-partner",   # under the trust controller, no mark
    "external":    FEDHOST + "/e/as-external",   # under a different anchor
    "unknown":     FEDHOST + "/e/as-unknown",    # no entity configuration (404)
}


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


def http_get(url):
    """GET, returning (status, body_text)."""
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="GET"),
                                    context=SSL_CTX, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, f"error: {e}"


def _jwt_payload(jwt):
    seg = jwt.split(".")[1]
    seg += "=" * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg))


def _railway_gql(query, variables):
    body = json.dumps({"query": query, "variables": variables}).encode("utf-8")
    req = urllib.request.Request(
        RAILWAY_API, data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + RAILWAY_TOKEN})
    with urllib.request.urlopen(req, context=SSL_CTX, timeout=15) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def pf_activity(entity):
    """Pull pingfederate-runtime's emitted logs via Railway and keep the federation-plugin +
    assertion-servlet lines. Best-effort: returns {enabled, lines|error} and never raises."""
    if not RAILWAY_TOKEN:
        return {"enabled": False, "hint": "create a Railway project token and set RAILWAY_TOKEN on pf-demo-ui"}
    try:
        dq = ("query($s:String!,$e:String!){deployments(first:1,input:{serviceId:$s,environmentId:$e})"
              "{edges{node{id}}}}")
        d = _railway_gql(dq, {"s": PF_SERVICE_ID, "e": PF_ENVIRONMENT_ID})
        if d.get("errors"):
            return {"enabled": True, "error": str(d["errors"])[:200], "lines": []}
        edges = (((d.get("data") or {}).get("deployments") or {}).get("edges") or [])
        if not edges:
            return {"enabled": True, "error": "no deployment found for the PF service", "lines": []}
        dep_id = edges[0]["node"]["id"]
        lq = "query($d:String!,$n:Int!){deploymentLogs(deploymentId:$d,limit:$n){message}}"
        lg = _railway_gql(lq, {"d": dep_id, "n": 500})
        if lg.get("errors"):
            return {"enabled": True, "error": str(lg["errors"])[:200], "lines": []}
        logs = (((lg.get("data") or {}).get("deploymentLogs")) or [])
        pat = re.compile(PF_LOG_FILTER)
        lines = [l.get("message", "") for l in logs if pat.search(l.get("message", ""))]
        if entity:
            slug = entity.rstrip("/").rsplit("/", 1)[-1]
            narrowed = [m for m in lines if slug and slug in m]
            lines = narrowed or lines
        return {"enabled": True, "lines": lines[-40:]}
    except Exception as e:  # noqa: BLE001
        return {"enabled": True, "error": str(e)[:200], "lines": []}


def resolve_as(profile):
    """Resolve the target AS as a federation entity, live. Reads its entity configuration
    (trust marks + authority hints) and runs a trust-controller /resolve to test whether it chains
    to our home anchor — what a holder-side TrustChainValidator would do before disclosing."""
    sub = AS_PROFILE_SUB.get(profile)
    if not sub:
        return {"error": "unknown profile", "resolvable": False}
    cfg_status, cfg_body = http_get(sub + "/.well-known/openid-federation")
    if cfg_status != 200:
        return {"sub": sub, "resolvable": False, "home": False, "anchor": None,
                "trust_marks": [], "reason": "no entity configuration (HTTP %s)" % cfg_status}
    try:
        cfg = _jwt_payload(cfg_body)
    except Exception as e:  # noqa: BLE001
        return {"sub": sub, "resolvable": False, "home": False, "anchor": None,
                "trust_marks": [], "reason": "unparseable entity configuration: %s" % e}
    marks = [m.get("id") for m in cfg.get("trust_marks", []) if isinstance(m, dict)]
    hints = cfg.get("authority_hints", [])
    org = cfg.get("metadata", {}).get("federation_entity", {}).get("organization_name", sub)
    q = urllib.parse.urlencode({"sub": sub, "trust_anchor": TRUST_CONTROLLER})
    res_status, res_body = http_get(TRUST_CONTROLLER + "/resolve?" + q)
    home = res_status == 200
    chain_len = None
    if home:
        try:
            chain_len = len(_jwt_payload(res_body).get("trust_chain", []))
        except Exception:  # noqa: BLE001
            pass
    return {"sub": sub, "resolvable": True, "home": home,
            "anchor": TRUST_CONTROLLER if home else (hints[0] if hints else None),
            "trust_marks": marks, "org": org, "chain_len": chain_len}


# AS-side federation client authentication: the demo "clients" map to real RP entities.
CLIENT_SUB = {
    "rp-sales":     FEDHOST + "/e/rp-sales",      # member, active, automatic, scoped
    "rp-legacy":    FEDHOST + "/e/rp-legacy",     # member, active, but explicit-only -> policy reject
    "rp-suspended": FEDHOST + "/e/rp-suspended",  # config exists, not enrolled -> status reject
}


def authorize_client(client_key, requested_scopes):
    """What PingFederate does at client authentication: resolve the CLIENT entity via the trust
    controller and authenticate only if it's a federation member, within policy, status-active,
    and the requested scopes are within the scopes registered to its entity metadata."""
    sub = CLIENT_SUB.get(client_key, client_key)
    requested = [s for s in requested_scopes if s]
    q = urllib.parse.urlencode({"sub": sub, "trust_anchor": TRUST_CONTROLLER})
    res_status, res_body = http_get(TRUST_CONTROLLER + "/resolve?" + q)
    checks = {"member": False, "status_active": False, "within_policy": False, "scope_ok": False}
    if res_status != 200:
        cfg_status, _ = http_get(sub + "/.well-known/openid-federation")
        exists = cfg_status == 200
        return {"authenticated": False, "sub": sub, "checks": checks,
                "requested_scopes": requested,
                "reason": ("entity configuration exists but is not enrolled in the federation "
                           "(suspended/revoked) — no resolvable chain to the trust anchor"
                           if exists else
                           "unknown entity — no federation entity configuration")}
    # PF verifies the resolve-response is signed by the anchor before trusting it: fetch the
    # anchor's published keys (GET /.well-known/openid-federation) and confirm the response's
    # signing key is one of them. (jose4j does the full ES512 verify in PF; here we match the kid.)
    acfg_status, acfg_body = http_get(TRUST_CONTROLLER + "/.well-known/openid-federation")
    anchor_kids = []
    if acfg_status == 200:
        try:
            anchor_kids = [k.get("kid") for k in _jwt_payload(acfg_body).get("jwks", {}).get("keys", [])]
        except Exception:  # noqa: BLE001
            pass
    try:
        hseg = res_body.split(".")[0]
        hseg += "=" * (-len(hseg) % 4)
        rr_kid = json.loads(base64.urlsafe_b64decode(hseg)).get("kid")
    except Exception:  # noqa: BLE001
        rr_kid = None
    anchor_signed = rr_kid is not None and rr_kid in anchor_kids
    checks["member"] = anchor_signed        # membership requires an anchor-signed resolve-response
    checks["status_active"] = anchor_signed  # resolvable subordinate + anchor-signed == active
    if not anchor_signed:
        return {"authenticated": False, "sub": sub, "checks": checks, "requested_scopes": requested,
                "anchor_signed": False,
                "reason": "resolve-response is not signed by the trust anchor — untrusted resolution"}
    try:
        rr = _jwt_payload(res_body)
    except Exception as e:  # noqa: BLE001
        return {"authenticated": False, "sub": sub, "checks": checks,
                "requested_scopes": requested, "reason": "unparseable resolve response: %s" % e}
    md = rr.get("metadata", {})
    oc = md.get("oauth_client")
    org = md.get("federation_entity", {}).get("organization_name", sub)
    chain_len = len(rr.get("trust_chain", []))
    if not oc:
        return {"authenticated": False, "sub": sub, "org": org, "checks": checks,
                "requested_scopes": requested, "chain_len": chain_len,
                "reason": "resolved entity has no oauth_client metadata"}
    reg_types = oc.get("client_registration_types", [])
    checks["within_policy"] = "automatic" in reg_types
    registered = set((oc.get("scope") or "").split())
    checks["scope_ok"] = set(requested).issubset(registered)
    authenticated = checks["within_policy"] and checks["scope_ok"]
    if authenticated:
        reason = "authenticated — federation member, within policy, requested scopes registered"
    elif not checks["within_policy"]:
        reason = ("within-policy check failed: automatic client registration not permitted "
                  "(client_registration_types=%s)" % reg_types)
    else:
        reason = "requested scope exceeds the scopes registered to the entity: %s" % sorted(set(requested) - registered)
    return {"authenticated": authenticated, "sub": sub, "org": org, "checks": checks,
            "reg_types": reg_types, "chain_len": chain_len,
            "registered_scopes": sorted(registered), "requested_scopes": requested,
            "granted_scopes": sorted(set(requested) & registered), "reason": reason}


# ── Day-0 bootstrap: enroll a browser-minted entity into the live federation ──
# The browser generates the ENTITY key (WebCrypto), self-signs the entity configuration
# and hands us the public JWK + signed JWT. This server then (a) hosts the entity
# configuration at /e/<slug>/.well-known/openid-federation (the demo UI's public URL is
# the entity_id) and (b) registers the public key as a subordinate record in the trust
# controller (admin API). The private key never leaves the browser.
ENROLLED = {}  # slug -> {"jwt": entity_config_jwt, "entity_id": sub}
ADMIN_SUBS = TRUST_CONTROLLER + "/api/v1/admin/subordinates"
SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


def http_json(method, url, obj=None):
    """JSON request, returning (status, body_text)."""
    data = json.dumps(obj).encode() if obj is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, f"error: {e}"


def resolve_sub(sub):
    """Ask the trust controller to resolve an entity — used for the before/after check."""
    q = urllib.parse.urlencode({"sub": sub, "trust_anchor": TRUST_CONTROLLER})
    status, body = http_get(TRUST_CONTROLLER + "/resolve?" + q)
    out = {"sub": sub, "resolved": status == 200, "status": status}
    if status == 200:
        out["resolve_jwt"] = body
        try:
            out["chain_len"] = len(_jwt_payload(body).get("trust_chain", []))
        except Exception:  # noqa: BLE001
            pass
    else:
        out["body"] = body[:300]
    return out


def trust_chain_for(sub):
    """Return the trust chain (list of entity-statement JWTs) the controller resolves for `sub`, so the client
    can carry it in its client_assertion trust_chain header for OpenID Federation §12.1 automatic
    registration at the token endpoint."""
    q = urllib.parse.urlencode({"sub": sub, "trust_anchor": TRUST_CONTROLLER})
    status, body = http_get(TRUST_CONTROLLER + "/resolve?" + q)
    if status == 200:
        try:
            chain = _jwt_payload(body).get("trust_chain", []) or []
            return {"resolved": True, "trust_chain": chain, "length": len(chain)}
        except Exception:  # noqa: BLE001
            return {"resolved": False, "trust_chain": [], "error": "unparseable resolve response"}
    return {"resolved": False, "trust_chain": [], "status": status, "error": (body or "")[:200]}


def remove_controller_record(entity_id):
    """DELETE any subordinate record(s) for entity_id. Returns True if one was removed."""
    status, body = http_get(ADMIN_SUBS)
    if status != 200:
        return False
    try:
        subs = json.loads(body)
    except Exception:  # noqa: BLE001
        return False
    removed = False
    for s in subs:
        if s.get("entity_id") == entity_id:
            st, _ = http_json("DELETE", "%s/%s" % (ADMIN_SUBS, s.get("id")))
            removed = removed or st in (200, 202, 204)
    return removed


def enroll_entity(payload):
    slug = (payload.get("slug") or "").strip().lower()
    jwt = payload.get("entity_config_jwt") or ""
    jwk = payload.get("jwk") or {}
    if not SLUG_RE.match(slug):
        return {"error": "invalid slug (lowercase letters, digits, hyphens)"}
    try:
        cfg = _jwt_payload(jwt)
    except Exception as e:  # noqa: BLE001
        return {"error": "unparseable entity configuration: %s" % e}
    sub = cfg.get("sub")
    if not sub or cfg.get("iss") != sub or not sub.endswith("/e/" + slug):
        return {"error": "entity configuration must be self-issued (iss == sub == …/e/%s)" % slug}
    keys = cfg.get("jwks", {}).get("keys", [])
    if not any(k.get("x") == jwk.get("x") and k.get("y") == jwk.get("y") for k in keys):
        return {"error": "the registered public JWK is not in the entity configuration's jwks"}
    # Host the entity configuration (this is what makes `sub` a live federation entity).
    ENROLLED[slug] = {"jwt": jwt, "entity_id": sub}
    # Write the controller record: replace any stale one, then register {entity_id, jwks}.
    replaced = remove_controller_record(sub)
    reg_status, reg_body = http_json("POST", ADMIN_SUBS,
                                     {"entity_id": sub, "jwks": {"keys": [jwk]}})
    # The anchor-signed subordinate statement IS "the record": fetch it back to show it.
    fq = urllib.parse.urlencode({"sub": sub})
    fetch_status, fetch_body = http_get(TRUST_CONTROLLER + "/fetch?" + fq)
    return {"entity_id": sub, "registered": reg_status in (200, 201),
            "replaced_stale_record": replaced, "admin_status": reg_status,
            "admin_body": None if reg_status in (200, 201) else reg_body[:400],
            "subordinate_statement": fetch_body if fetch_status == 200 else None,
            "fetch_status": fetch_status}


def deregister_entity(payload):
    """Suspend: remove the controller record but KEEP serving the entity configuration —
    the entity still exists, the federation just no longer vouches for it."""
    entity_id = payload.get("entity_id") or ""
    if not any(e["entity_id"] == entity_id for e in ENROLLED.values()):
        return {"error": "not an entity enrolled by this demo"}
    return {"entity_id": entity_id, "deregistered": remove_controller_record(entity_id)}


def list_subordinates():
    """List the subordinate records the trust controller (anchor) currently holds — the live federation
    membership. Marks the entities this demo minted (present in ENROLLED)."""
    status, body = http_get(ADMIN_SUBS)
    subs = []
    if status == 200:
        try:
            enrolled = {e["entity_id"] for e in ENROLLED.values()}
            for s in json.loads(body):
                eid = s.get("entity_id") or ""
                subs.append({"id": s.get("id"), "entity_id": eid, "demo": eid in enrolled})
        except Exception:  # noqa: BLE001
            pass
    subs.sort(key=lambda s: (not s["demo"], s["entity_id"]))
    return {"anchor": TRUST_CONTROLLER, "status": status, "count": len(subs), "subordinates": subs}


def list_pf_clients():
    """List the OAuth clients this module has registered into PingFederate — via the plugin's own read-only
    endpoint (no PF admin API / credentials needed). Each entry is tagged 'explicit' or 'automatic'. Returns
    available=False (rather than an error) when the endpoint is absent, so the UI can show a 'pending
    plugin redeploy' state instead of failing."""
    status, body = http_get(PF_CLIENTS_URL)
    if status == 200:
        try:
            d = json.loads(body)
            clients = d.get("clients", [])
            return {"available": True, "status": status, "count": d.get("count", len(clients)), "clients": clients}
        except Exception:  # noqa: BLE001
            return {"available": False, "status": status, "clients": [], "error": "unparseable response"}
    return {"available": False, "status": status, "clients": [], "error": (body or "")[:200]}


def reset_demo(payload):
    """Reset the demo: withdraw the controller records for every entity this demo enrolled (matched by the
    caller's origin and by ENROLLED) and stop hosting their configurations. Never touches the pre-registered
    federation members — those live under a different origin (fedhost), so the prefix match excludes them."""
    origin = (payload.get("origin") or "").rstrip("/")
    prefix = origin + "/e/" if origin else None
    targets = {e["entity_id"] for e in ENROLLED.values()}
    status, body = http_get(ADMIN_SUBS)
    if status == 200 and prefix:
        try:
            for s in json.loads(body):
                eid = s.get("entity_id") or ""
                if eid.startswith(prefix):
                    targets.add(eid)
        except Exception:  # noqa: BLE001
            pass
    removed = [eid for eid in sorted(targets) if remove_controller_record(eid)]
    for slug in [s for s, e in ENROLLED.items() if not prefix or e["entity_id"].startswith(prefix)]:
        ENROLLED.pop(slug, None)
    return {"reset": True, "removed_records": removed, "count": len(removed)}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="application/json"):
        b = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
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
                "token_aud": PF_TOKEN_AUD,
                "pf_clients_url": PF_CLIENTS_URL,
                "console_url": CONSOLE_URL,
                "client_id": CLIENT_ID,
                "git_commit": GIT_COMMIT,
                "software_version": SOFTWARE_VERSION,
                "trust_controller": TRUST_CONTROLLER,
                "workload_url": WORKLOAD_URL,
            }))
        elif self.path.startswith("/api/resolvesub"):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            sub = (q.get("sub") or [""])[0]
            self._send(200, json.dumps(resolve_sub(sub)))
        elif self.path.startswith("/api/resolve"):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            profile = (q.get("profile") or ["home-emea"])[0]
            self._send(200, json.dumps(resolve_as(profile)))
        elif self.path.startswith("/e/"):
            m = re.match(r"^/e/([a-z0-9-]+)/\.well-known/openid-federation$", self.path)
            e = ENROLLED.get(m.group(1)) if m else None
            if e:
                self._send(200, e["jwt"], "application/entity-statement+jwt")
            else:
                self._send(404, json.dumps({"error": "unknown entity"}))
        elif self.path.startswith("/api/authorize"):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            client = (q.get("client") or ["rp-sales"])[0]
            scopes = (q.get("scope") or [""])[0].split()
            self._send(200, json.dumps(authorize_client(client, scopes)))
        elif self.path.startswith("/api/subordinates"):
            self._send(200, json.dumps(list_subordinates()))
        elif self.path.startswith("/api/pf-clients"):
            self._send(200, json.dumps(list_pf_clients()))
        elif self.path.startswith("/api/pf-activity"):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            entity = (q.get("entity") or [""])[0]
            self._send(200, json.dumps(pf_activity(entity)))
        elif self.path.startswith("/api/trust-chain"):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            sub = (q.get("sub") or [""])[0]
            self._send(200, json.dumps(trust_chain_for(sub)))
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
        elif self.path == "/api/token-federation":
            # OpenID Federation §12.1 automatic registration: the client authenticates purely with its own
            # private_key_jwt assertion (which carries its trust chain) — NO demo client_id/secret injected.
            # PingFederate provisions the client on the fly (the token-endpoint auto-registration filter),
            # then authenticates this very request.
            payload = json.loads(raw or b"{}")
            form = payload.get("form", {})
            headers = payload.get("headers", {})
            data = "&".join(f"{urllib.parse.quote(k)}={urllib.parse.quote(v)}"
                            for k, v in form.items()).encode()
            headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
            status, body, hdrs = pf_post(TOKEN_ENDPOINT, data=data, headers=headers)
            self._send(200, json.dumps({"status": status, "body": body}))
        elif self.path == "/api/enroll":
            try:
                payload = json.loads(raw or b"{}")
            except Exception:  # noqa: BLE001
                payload = {}
            self._send(200, json.dumps(enroll_entity(payload)))
        elif self.path == "/api/deregister":
            try:
                payload = json.loads(raw or b"{}")
            except Exception:  # noqa: BLE001
                payload = {}
            self._send(200, json.dumps(deregister_entity(payload)))
        elif self.path == "/api/reset":
            try:
                payload = json.loads(raw or b"{}")
            except Exception:  # noqa: BLE001
                payload = {}
            self._send(200, json.dumps(reset_demo(payload)))
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
