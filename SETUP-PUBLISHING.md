# Setting up publishing to Maven Central

One-time, by a maintainer, on a machine that is not CI. Nothing here can live in the repository.

## 1. The namespace

`io.github.jason-te-sde` is verified by owning the GitHub account of the same name.

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with GitHub.
2. **Namespaces → Add namespace → `io.github.jason-te-sde`.**
3. It gives you a verification token. Create a **public** repository on that account named after the
   token, exactly. Then press verify and delete the repository.

## 2. A publishing token

**Account → Generate User Token.** It gives a username and a password that are not your login.

These become the `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` repository secrets.

## 3. A signing key

Central requires every artifact to be signed.

```bash
gpg --full-generate-key        # RSA 4096, no expiry, the address on your commits
gpg --list-secret-keys --keyid-format=long
```

Publish the public half so that Central can check the signature:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Export the private half for CI:

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

That whole block, including the `-----BEGIN`/`-----END` lines, is the `GPG_PRIVATE_KEY` secret. The
passphrase is `GPG_PASSPHRASE`.

## 4. The secrets

**Settings → Secrets and variables → Actions**, in an environment named `maven-central` so that the
job cannot run from a fork:

| | |
| --- | --- |
| `CENTRAL_USERNAME` | from step 2 |
| `CENTRAL_PASSWORD` | from step 2 |
| `GPG_PRIVATE_KEY` | the armoured private key from step 3 |
| `GPG_PASSPHRASE` | its passphrase |

## 5. Check it locally first

```bash
mvn -B clean verify -Prelease,publish -DskipTests
```

This signs without uploading. If it hangs, the passphrase is not reaching GPG: the build passes
`--pinentry-mode loopback` for exactly that reason, because without it a signing run on a machine
with no terminal waits forever on a dialog nobody will see.

## 6. Then

[`RELEASING.md`](RELEASING.md).

## What can go wrong

| | |
| --- | --- |
| `401` from Central | the token, not the login. Regenerate it |
| "Missing signature" | the `publish` profile was not active, or GPG found no key |
| "Invalid signature" | the public key has not reached a keyserver, or has not propagated yet. Wait |
| "Missing javadoc/sources" | the `release` profile was not active |
| The build hangs during signing | `--pinentry-mode loopback` is missing, or `GPG_PASSPHRASE` is not set |
