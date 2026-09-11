# Security

## Reporting

Open a [private security advisory](https://github.com/jason-te-sde/till/security/advisories/new).
Please do not open a public issue for anything you think is exploitable.

Expect an acknowledgement within a few days. This is a single-maintainer project, so a fix is
best-effort and on a timescale to be agreed rather than promised.

## What till defends against

| | |
| --- | --- |
| Unauthenticated access | Two bearer tokens. Every `/v1` endpoint needs one; changing stock levels directly needs the other |
| Accidental exposure | The service **refuses to start** unauthenticated on a non-loopback address unless `till.insecure=true` |
| Privilege creep | A client token cannot adjust stock. A checkout service that is compromised can hold and release stock; it cannot zero an inventory |
| Timing attacks on tokens | Compared with `MessageDigest.isEqual`. Not the interesting attack surface here, but a variable-time compare on a secret is the kind of thing that gets copied somewhere it matters |
| Injection | Every statement is a prepared statement with bound parameters. Identifiers are additionally restricted at the boundary to `A-Za-z0-9._:@=+/-`, which is narrower than it needs to be for SQL and wide enough for real SKUs |
| Log and metric injection | The same character restriction. A SKU cannot contain a newline, so it cannot forge a log line or a metric label |
| Unbounded input | SKUs 64 characters, keys 128, quantities capped, time-to-live capped, request bodies bounded by the container's limit |
| A denial of service through contention | Bounded attempts, then a 503 with `Retry-After`. One hot SKU cannot park every thread on a lock, because there are no locks |
| Management endpoints on the public port | Health and metrics are on a separate port by default. `show-details: never` on health, so a probe does not report the database's hostname to an unauthenticated caller |

## What it does not

Stated here rather than discovered.

- **Data at rest is not encrypted** beyond whatever the disk and the database do. Stock levels and
  reservation identifiers are stored in plain text.
- **Tokens are static.** There is no rotation without a restart, no expiry, and no per-caller
  identity. A leaked token is valid until the service is restarted with a new one.
- **There is no rate limiting and no per-caller quota.** A caller with a valid token can reserve
  everything you have, repeatedly. That belongs at the edge, where the identity of the caller is
  known.
- **There is no audit of who did what.** The outbox records what happened, not which token asked for
  it.
- **The tokens are in `docker-compose.yml` in plain text.** That file is a demonstration. A real
  deployment gets them from somewhere else.
- **Nothing is hardened against a hostile database.** A compromised PostgreSQL can say anything, and
  till will believe most of it. The check constraint in the schema is a guard against a bug in till,
  not against an attacker with write access.
- **Transport is the deployment's problem.** till speaks plain HTTP and expects to be behind
  something that terminates TLS. There is no option to serve HTTPS directly, because doing it well
  means certificate reloading and cipher policy, and something in front of it already does.
- **No dependency signing or SBOM.** Dependencies are pinned by version and updated by Dependabot;
  nothing verifies their provenance.

## Supported versions

The latest release. This is a young project; there is no long-term support branch.
