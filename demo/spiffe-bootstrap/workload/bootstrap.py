#!/usr/bin/env python3
"""
The workload: bootstrap a Client Attestation from a SPIFFE JWT-SVID, then use it to get a token.

This is the client half of the SPIFFE -> Client Attestation bootstrap. It runs in the docker-compose
demo as the `workload` container (SPIRE agent socket mounted), and mirrors the Java harness so you can
verify the exact same flow without Docker.

Steps:
  1. Obtain a JWT-SVID for this workload's SPIFFE identity.
       - compose:   `spire-agent api fetch jwt -audience <ATTESTER> -socketPath <sock>`
       - local dev: GET <ISSUER_BASE>/dev/svid   (the harness mints one its bundle trusts)
       - or set SVID_JWT directly.
  2. Generate a per-instance EC P-256 key (the workload's own key; never leaves here).
  3. POST { client_id, instance_key(pub), svid, proof } to <PF>/federation/attestation
     -> PingFederate validates the SVID against the registered SPIFFE bundle and MINTS a Client
        Attestation bound to the instance key.
  4. Build a PoP over the token endpoint and POST the minted attestation + PoP to <PF>/as/token.oauth2
     -> a real access token, authenticated purely from the workload's infra identity.

Only PyJWT + cryptography are required.
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request
import uuid

import jwt  # PyJWT
from cryptography.hazmat.primitives.asymmetric import ec

# ── configuration (env-overridable; defaults match the harness + compose) ──────────────────────────
PF_BASE = os.environ.get("PF_BASE", "http://localhost:9031").rstrip("/")
ISSUER_BASE = os.environ.get("ISSUER_BASE", PF_BASE).rstrip("/")          # where /dev/svid + issuance live
ATTESTER = os.environ.get("ATTESTER_ISSUER", "https://attester.banking.demo")
CLIENT_ID = os.environ.get("CLIENT_ID", "https://payment-agent.banking.demo")
OP_ISSUER = os.environ.get("OP_ISSUER", "https://as.banking.demo")
TOKEN_ENDPOINT = os.environ.get("TOKEN_ENDPOINT", PF_BASE + "/as/token.oauth2")
SPIFFE_ID = os.environ.get("SPIFFE_ID", "spiffe://banking.demo/payment-agent")
AGENT_SOCKET = os.environ.get("SPIRE_AGENT_SOCKET", "/tmp/spire-agent/public/api.sock")
PROOF_TYP = "oauth-attestation-instance-proof+jwt"   # InstanceKeyProofValidator.TYP


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def post_json(url: str, body: dict, headers: dict) -> tuple[int, dict]:
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def post_form(url: str, form: str, headers: dict) -> tuple[int, dict]:
    req = urllib.request.Request(url, data=form.encode(), method="POST",
                                 headers={"Content-Type": "application/x-www-form-urlencoded", **headers})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def fetch_svid() -> str:
    """Step 1 — obtain the JWT-SVID from whichever source is available."""
    if os.environ.get("SVID_JWT"):
        return os.environ["SVID_JWT"].strip()
    if os.environ.get("USE_DEV_SVID") == "1" or "/dev/svid" in os.environ.get("SVID_URL", ""):
        url = os.environ.get("SVID_URL", ISSUER_BASE + "/dev/svid")
        with urllib.request.urlopen(url, timeout=20) as r:
            return json.loads(r.read())["svid"].strip()
    # compose path: ask the SPIRE agent over the Workload API.
    out = subprocess.run(
        ["spire-agent", "api", "fetch", "jwt", "-audience", ATTESTER, "-socketPath", AGENT_SOCKET],
        capture_output=True, text=True, timeout=30)
    for line in out.stdout.splitlines():
        line = line.strip()
        if line.count(".") == 2 and not line.startswith("token("):
            return line
        if line.startswith("token("):                 # "token(spiffe://…): eyJ…"
            return line.split("):", 1)[1].strip()
    raise SystemExit(f"could not parse SVID from spire-agent output:\n{out.stdout}\n{out.stderr}")


def jwk_public(pub) -> dict:
    nums = pub.public_numbers()
    size = (pub.curve.key_size + 7) // 8
    return {"kty": "EC", "crv": "P-256", "kid": "instance-1",
            "x": b64u(nums.x.to_bytes(size, "big")), "y": b64u(nums.y.to_bytes(size, "big"))}


def sign(key, typ: str, claims: dict) -> str:
    return jwt.encode(claims, key, algorithm="ES256", headers={"typ": typ, "kid": "instance-1"})


def main() -> int:
    print("== workload: SPIFFE -> Client Attestation bootstrap ==")
    print(f"   PF={PF_BASE}  attester={ATTESTER}  spiffe_id={SPIFFE_ID}")

    # 1. JWT-SVID
    svid = fetch_svid()
    svid_claims = jwt.decode(svid, options={"verify_signature": False})
    print(f"\n1) JWT-SVID: sub={svid_claims.get('sub')} aud={svid_claims.get('aud')}")

    # 2. per-instance key
    key = ec.generate_private_key(ec.SECP256R1())
    instance_pub = jwk_public(key.public_key())

    # 3. bootstrap: SVID + instance key + PoP -> minted attestation
    proof = sign(key, PROOF_TYP, {"aud": ATTESTER, "jti": str(uuid.uuid4()), "iat": _now()})
    body = {"client_id": CLIENT_ID, "instance_key": instance_pub, "svid": svid, "proof": proof,
            "authorization_details": [{"type": "sales_agent", "sales_regions": ["EMEA"]}]}
    status, resp = post_json(ISSUER_BASE + "/federation/attestation", body, {})
    if status != 200:
        print(f"\n2) /federation/attestation -> {status}: {resp}", file=sys.stderr)
        return 1
    attestation = resp["attestation"]
    hdr = json.loads(base64.urlsafe_b64decode(attestation.split(".")[0] + "=="))
    print(f"\n2) /federation/attestation -> 200. Minted Client Attestation (typ={hdr.get('typ')}, "
          f"expires_in={resp.get('expires_in')})")

    # 4. use the attestation to get a token
    token_pop = sign(key, "oauth-client-attestation-pop+jwt",
                     {"iss": CLIENT_ID, "aud": OP_ISSUER, "jti": "pop-" + str(uuid.uuid4()), "iat": _now()})
    status, tok = post_form(TOKEN_ENDPOINT, "grant_type=client_credentials",
                            {"OAuth-Client-Attestation": attestation, "OAuth-Client-Attestation-PoP": token_pop})
    if status != 200:
        print(f"\n3) token endpoint -> {status}: {tok}", file=sys.stderr)
        return 1
    at = tok.get("access_token", "")
    print(f"\n3) {TOKEN_ENDPOINT} (attestation + PoP) -> 200")
    print(f"   access_token={at[:36]}...  token_type={tok.get('token_type')}")
    print("\nBOOTSTRAP OK — turned a SPIFFE SVID into a token via the PingFederate issuance servlet.")
    return 0


def _now() -> int:
    import time
    return int(time.time())


if __name__ == "__main__":
    sys.exit(main())
