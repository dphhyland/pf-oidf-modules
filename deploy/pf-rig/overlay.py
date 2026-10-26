#!/usr/bin/env python3
"""
The rig's additions to the archive tools/profile.py authors, applied through the authoring PF's admin API
(the container `profile.py author --keep` leaves running), then the archive exported again.

pf-agentic-identity's Terraform makes the JWT access token manager with no audience and only `sub`. The
observatory's /runner API checks `aud` and reads the instance from `agent_id` (rs-validation rules), so:

  1. OGNL expressions on (config store org.sourceid.common.ExpressionManager, evaluateExpressions=true);
  2. the manager's Audience Claim Value = AUDIENCE, and `agent_id` added to its attribute contract;
  3. the client-credentials mapping fulfils `agent_id` from the verified Client Attestation:
     @...ClientAttestationUtils@attestationClaim(#this,"agent_id") - read from the context the
     token-endpoint filter publishes after verifying, never from the presented header;
  4. GET /configArchive/export -> --out.

  overlay.py --admin https://localhost:29999 --author-env <out>/author.env --manager observatoryRigJwt \
             --audience https://devops.mydigitalid.info/runner --out data.zip

The admin password is read from author.env (PING_IDENTITY_PASSWORD) and never printed. The admin listener's
certificate is the authoring container's self-signed one, so it is not verified: run this only against a
container on this machine.
"""
import argparse, base64, json, ssl, sys, urllib.parse, urllib.request

AGENT_ID = '@com.pingidentity.ps.oidf.servlet.clientregistration.utils.ClientAttestationUtils@attestationClaim(#this,"agent_id")'

a = argparse.ArgumentParser()
a.add_argument("--admin", required=True)
a.add_argument("--author-env", required=True)
a.add_argument("--manager", required=True)
a.add_argument("--audience", required=True)
a.add_argument("--out", required=True)
args = a.parse_args()
if not args.admin.startswith("https://localhost:"):
    sys.exit("--admin must be the local authoring container (https://localhost:<port>)")

pw = None
for line in open(args.author_env, encoding="utf-8"):
    k, _, v = line.strip().partition("=")
    if k == "PING_IDENTITY_PASSWORD":
        pw = v.strip().strip('"').strip("'")
if not pw:
    sys.exit("no PING_IDENTITY_PASSWORD in --author-env")
auth = "Basic " + base64.b64encode(f"administrator:{pw}".encode()).decode()
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
API = args.admin.rstrip("/") + "/pf-admin-api/v1"

def call(method, path, body=None, raw=False):
    req = urllib.request.Request(API + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": auth, "X-XSRF-Header": "PingFederate", "Accept": "application/json",
                                          **({"Content-Type": "application/json"} if body is not None else {})})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=120) as r:
            data = r.read()
            return data if raw else (json.loads(data) if data else None)
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path}: HTTP {e.code} {e.read()[:800].decode(errors='replace')}")

# 1. expressions
call("PUT", "/configStore/org.sourceid.common.ExpressionManager/evaluateExpressions",
     {"type": "STRING", "id": "evaluateExpressions", "stringValue": "true"})
print("expressions: on")

# 2. the manager: audience, agent_id
m = call("GET", f"/oauth/accessTokenManagers/{args.manager}")
fields = [f for f in m["configuration"]["fields"] if f["name"] != "Audience Claim Value"]
fields.append({"name": "Audience Claim Value", "value": args.audience})
m["configuration"]["fields"] = fields
ext = m["attributeContract"].setdefault("extendedAttributes", [])
if not any(x["name"] == "agent_id" for x in ext):
    ext.append({"name": "agent_id", "multiValued": False})
call("PUT", f"/oauth/accessTokenManagers/{args.manager}", m)
print(f"manager {args.manager}: aud {args.audience}, agent_id in the contract")

# 3. the client-credentials mapping
maps = call("GET", "/oauth/accessTokenMappings")
cc = [x for x in maps if x["context"]["type"] == "CLIENT_CREDENTIALS" and x["accessTokenManagerRef"]["id"] == args.manager]
if len(cc) != 1:
    sys.exit(f"expected one CLIENT_CREDENTIALS mapping for {args.manager}, found {len(cc)}")
mp = cc[0]
mp["attributeContractFulfillment"]["agent_id"] = {"source": {"type": "EXPRESSION"}, "value": AGENT_ID}
call("PUT", "/oauth/accessTokenMappings/" + urllib.parse.quote(mp["id"], safe=""), mp)
print(f"mapping {mp['id']}: agent_id from the verified attestation")

# 4. export
data = call("GET", "/configArchive/export", raw=True)
with open(args.out, "wb") as fh:
    fh.write(data)
print(f"archive: {args.out} ({len(data)} bytes) - a SECRET (it holds pf.jwk): encrypt it, never commit it")
