# age recipients

One file per environment, each holding the **public** age recipient the config archive for that
environment is encrypted to. Public keys — safe to commit, and the point of committing them is that
anyone re-exporting an archive encrypts it to the right recipient without having to be told which.

```
staging.txt      age1...
production.txt   age1...
```

The matching **identities** are secret and live in exactly two places: the operator's password manager,
and a sealed Railway variable `PF_ARCHIVE_AGE_KEY` on the service for that environment. Never here.

## Creating one

```sh
age-keygen -o identity.txt                                   # identity.txt is the SECRET half
grep -o 'age1[a-z0-9]*' identity.txt > staging.txt           # the public half, committed
# put the contents of identity.txt into the password manager and the Railway variable, then:
shred -u identity.txt
```

## Encrypting an archive

```sh
age -r "$(cat age-recipients/staging.txt)" -o data.zip.age data.zip
shred -u data.zip
```

`terraform/helpers/export-data-zip.sh` should do this on export, so a plaintext archive never sits on
disk longer than the moment it takes to encrypt it.

## Why

A PF configArchive is a plain zip that **contains** `pf.jwk` — the master key that decrypts every
secret inside it. Committing one publishes the key; baking one into an image layer publishes the key.
This project has done both. Encrypted, the archive is safe in git and safe in a layer, and the only
secret is the identity — which is in neither.

Nothing is generated here yet: the recipients are created as part of the master-key rotation, so that
the first archive encrypted to them is one the compromised key never touched.
