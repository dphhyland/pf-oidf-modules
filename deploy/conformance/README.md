# The conformance rig's Railway deployment

The rig itself - Terraform, the suite configurations, the scripts that author, export and test a
PingFederate against the [OpenID Foundation conformance suite](https://www.certification.openid.net/) -
lives in [pf-agentic-identity/conformance](https://github.com/dphhyland/pf-agentic-identity/tree/main/conformance)
since 2026-09-24. Conformance is the capability's own concern, and its README is the runbook.

What stays here is the one thing that repo does not do: deploy. Railway project `pf-conformance`
(`3c876ea8`), one service, `pingfederate`, behind a TCP proxy that passes TLS through to 9031, so the
suite gets PingFederate's own listener. Delete the project when the certification runs are done.

```sh
export PF_AGENTIC_IDENTITY_HOME=../../../pf-agentic-identity        # a worktree is fine
PF_BASE_URL=https://thomas.proxy.rlwy.net:50715 "$PF_AGENTIC_IDENTITY_HOME/conformance/up.sh"   # author, export, build - the archive must carry THIS issuer
./set-secrets.sh                                                    # a PERSON runs this: DevOps licence credentials + the introspection secret
./set-vars.sh                                                       # the rig's vars.env onto the service, issuer substituted
railway up "$("$PF_AGENTIC_IDENTITY_HOME/conformance/compose-context.sh")" --path-as-root --service pingfederate --no-gitignore --ci
```

Both scripts read the rig's `secrets.env` and `vars.env` from that checkout. `set-vars.sh` makes the
same issuer substitution `up.sh` does and leaves the introspection endpoint on loopback, for the reason
the rig's README gives. `railway.json` is the service's build and healthcheck settings.

Results, hosted-suite notes and everything about why the rig is shaped as it is: the rig's README.
