"""A real agent workload. On boot it attests itself via the SPIFFE agent (SPIRE Workload API
analogue) and obtains a JWT-SVID; remotely invoke POST /invoke to run the token exchange — build the
SPIFFE-attested Client Attestation and present it to PingFederate. Uses the client_attestation_sdk."""
import json, os, time, urllib.parse, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from client_attestation_sdk.keys import SigningKeyPair
from client_attestation_sdk.spiffe import SpiffeAgent, RegistrationEntry, to_workload_claim
from client_attestation_sdk.builders import ClientAttestationBuilder, PopBuilder

PF_TOKEN_ENDPOINT = os.environ.get("PF_TOKEN_ENDPOINT", "https://pingfederate-runtime-production.up.railway.app/as/token.oauth2")
PF_TOKEN_AUD    = os.environ.get("PF_TOKEN_AUD", "https://localhost:9031")   # PF validates the PoP `aud` against its configured base
CLIENT_ID       = os.environ.get("CLIENT_ID", "https://rp.example.com")
CLIENT_SECRET   = os.environ.get("CLIENT_SECRET", "demo-secret-123")
ATTESTER_ISSUER = os.environ.get("ATTESTER_ISSUER", "https://attester.example.com")
TRUST_DOMAIN    = os.environ.get("SPIFFE_TRUST_DOMAIN", "banking.demo")
AGENT_TYPE      = os.environ.get("AGENT_TYPE", "payment-agent")
PORT            = int(os.environ.get("PORT", "8080"))

# Pre-shared mock attester (its public half is pre-trusted in PingFederate's mock-attesters file).
MOCK_ATTESTER_JWK = {"kty":"EC","kid":"mock-attester-1","crv":"P-256",
  "x":"c2pTtxD_E2ZGIMam9QGsiDvlY57axE9Q9LKSnidQUag",
  "y":"ZI_wiUp0BUd_Gmi9412cAet7vBMhi4fkwclL_ujlTSI",
  "d":"9TAjv9_QP_mzZOn0NIWeERR_gtXjcqqj8KDp-XX-C84"}
ENTITLEMENT = [{"type":"sales_agent","actions":["read_accounts","create_opportunity","submit_quote"],
  "locations":["https://crm.contoso.com/api"],"sales_regions":["EMEA"],"privileges":["quota:standard"]}]
SELECTORS = {"docker:image": AGENT_TYPE, "unix:uid": "0"}

# ── boot: the workload comes up and attests itself via the SPIFFE agent ──
print(f"[agent-workload] {AGENT_TYPE} instance booting…", flush=True)
attester_key = SigningKeyPair.from_jwk(MOCK_ATTESTER_JWK, "ES256")   # signs the client attestation (the type/attester)
attester_key.key_id = MOCK_ATTESTER_JWK["kid"]                        # header kid must match PF's mock-attesters JWKS ("mock-attester-1")
instance_key = SigningKeyPair.generate("ES256")                       # the instance local key (cnf / PoP)
spiffe_key   = SigningKeyPair.generate("ES256")                       # the SPIFFE agent's trust-domain key
AGENT = SpiffeAgent(TRUST_DOMAIN, spiffe_key, [
    RegistrationEntry(SELECTORS, f"spiffe://{TRUST_DOMAIN}/{AGENT_TYPE}",
        {"workload_type": AGENT_TYPE, "region": "EMEA", "environment": "prod",
         "entitlements": ["read_accounts", "create_opportunity", "submit_quote"]})
])
_BOOT_SVID = AGENT.fetch_jwt_svid(SELECTORS, ATTESTER_ISSUER)
print(f"[agent-workload] SPIFFE-attested → {_BOOT_SVID.spiffe_id}", flush=True)
print(f"[agent-workload] instance key kid: {instance_key.key_id}", flush=True)
print(f"[agent-workload] READY · POST /invoke runs the token exchange against {PF_TOKEN_ENDPOINT}", flush=True)


def token_exchange():
    svid = AGENT.fetch_jwt_svid(SELECTORS, ATTESTER_ISSUER)           # fresh SVID per invocation
    attestation = (ClientAttestationBuilder(attester_key, ATTESTER_ISSUER)
                   .client_id(CLIENT_ID).confirmation_key(instance_key)
                   .workload(to_workload_claim(svid)).authorization_details(ENTITLEMENT)
                   .expires_in(600).build())
    pop = PopBuilder(instance_key).client_id(CLIENT_ID).audience(PF_TOKEN_AUD).build()
    form = urllib.parse.urlencode({"grant_type": "client_credentials",
                                   "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET}).encode()
    req = urllib.request.Request(PF_TOKEN_ENDPOINT, data=form, method="POST", headers={
        "Content-Type": "application/x-www-form-urlencoded",
        "OAuth-Client-Attestation": attestation, "OAuth-Client-Attestation-PoP": pop})
    status, body = 0, ""
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            status, body = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read().decode()
    except Exception as e:  # noqa: BLE001
        status, body = -1, str(e)
    return {"spiffe_id": svid.spiffe_id, "svid": svid.token, "svid_attributes": svid.attributes,
            "attestation": attestation, "pop": pop, "pf_status": status, "pf_body": body}


class H(BaseHTTPRequestHandler):
    def _s(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type"); self.end_headers()
    def do_GET(self):
        if self.path.startswith("/identity"):
            svid = AGENT.fetch_jwt_svid(SELECTORS, ATTESTER_ISSUER)
            return self._s(200, {"status": "up", "agent_type": AGENT_TYPE, "trust_domain": TRUST_DOMAIN,
                "spiffe_id": svid.spiffe_id, "svid": svid.token, "attributes": svid.attributes,
                "selectors": SELECTORS, "instance_key_kid": instance_key.key_id,
                "pf_token_endpoint": PF_TOKEN_ENDPOINT})
        return self._s(200, {"status": "up", "agent_type": AGENT_TYPE, "spiffe_id": _BOOT_SVID.spiffe_id})
    def do_POST(self):
        if self.path.startswith("/invoke"):
            print("[agent-workload] /invoke — running the SPIFFE-attested token exchange", flush=True)
            r = token_exchange()
            print(f"[agent-workload] PingFederate responded HTTP {r['pf_status']}", flush=True)
            return self._s(200, r)
        return self._s(404, {"error": "not_found"})
    def log_message(self, *a):
        pass


ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
