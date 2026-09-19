# Changelog

Notable changes, newest first. This project follows [semantic versioning](https://semver.org) from
1.0 onwards; until then, a minor bump means a wire format, a schema, or the `Ledger` contract
changed, and a patch means everything else.

The public surface is `Ledger`, `Kernel`, `Command`, `Outcome`, and the HTTP API. `till-testkit` is
explicitly not: it is a testing tool and it will change.

## [Unreleased]

### Added

- **`till-kafka`** — publishes the outbox to Kafka with plain `kafka-clients`. `acks=all` and
  producer idempotence; records keyed by the entity the event is about, so one reservation's
  lifecycle cannot arrive out of order; deduplication key, outbox sequence, type and decision
  instant in headers. A failed batch throws, so the whole batch is offered again.
- **`till-catalogue`** — the storefront. Owns games, prices and a read model of availability built
  by consuming those events, and reserves by calling till over HTTP with a client token on a
  separate database. Its `available` is a cache with a timestamp: a stale number costs one refused
  checkout and can never cause an oversell.
- **An inbox on the consumer.** Events are applied exactly once by inserting the producer's
  deduplication key in the same transaction as the numbers it moves. The reservation events carry
  deltas, so a redelivery would otherwise corrupt the projection permanently.
- `EventPublisher` moved from `io.till.server` to `io.till.core`, so an adapter can depend on the
  kernel without depending on the application. Not part of the declared public surface, so no
  version bump; noted here because it is a package change.
- Kafka in the compose stack, both services from one image, and CI steps that drive a sale through
  the storefront and assert the storefront cannot move stock.

- **Retention runs itself.** `Retention` is a port on the ledger with bounded-batch deletes for the
  three tables that grow; `RetentionSweeper` runs them hourly. The idempotency window defaults to
  seven days — the setting to raise rather than lower — and reservations default to *keep forever*.
- **A request id on every request.** `X-Request-Id` is accepted, minted when absent, echoed on the
  response, put in the logging pattern, and carried in the body of every error.
- **RFC 9457 problems are in the contract.** Every non-2xx response now declares a `Problem` schema
  instead of inheriting the success type, and `OpenApiContractTest` checks that declaration against a
  real refusal taken off the wire.
- Authentication denials carry `code: UNAUTHORIZED` or `code: FORBIDDEN`, because by status alone a
  caller cannot tell "sign in" from "ask for a different token".
- `till_auth_denied_total{reason}` and `till_retention_deleted_total{table}`;
  `till_command_seconds` is published as a histogram, so a quantile across instances is a real one.
- The console: an error boundary inside the shell, so a panel that throws leaves the navigation
  usable; polling stops while the tab is hidden and catches up the moment it comes back; a favicon.

### Fixed

- **A broker outage would have held the outbox publisher's thread for a minute per batch.**
  `max.block.ms` bounds `send()`'s wait for cluster metadata and defaults to sixty seconds
  independently of the delivery timeout. Derived from the budget now, with a test that asserts it.
- **The service refused to start once Kafka was on the classpath.** `@ConditionalOnProperty` matches
  on a property being *present*, and the YAML default is present and empty.
- **Pruning an idempotency record while its event was still in the outbox could wedge that command
  permanently at 503.** An adjustment's event is named after the key; forgetting the key early let a
  re-execution collide with its own leftover event. The delete now refuses while the event is
  present.
- **`till_outbox_backlog` reported correctly only while the publisher was working.** It is now read
  through to the table on scrape, so it is right when the publisher is the broken thing.
- **Two administrative listings were sequential scans** (`V2__listing_indexes.sql`). `order by sku
  collate "C"` could not use the primary key's index, and the reservation listing filtered on a
  column its index did not contain.
- `markPublished` took its timestamp from the database rather than the injected clock.

## [0.1.0] — 2026-09-11

First release.

### The kernel

- `Kernel.decide(Snapshot, Command, Instant)`: the reservation rules as a pure function. No fields,
  no clock, no I/O, no threads.
- Reserve, commit, release, adjust and sweep, with all-or-nothing multi-SKU holds and every shortfall
  reported rather than the first.
- Idempotency keyed by the caller, with a fingerprint that ignores the server-minted reservation id
  and the order of the lines, so a retry is recognised as a retry.
- Deadlines decide expiry, not stored state: a hold past its deadline is expired whether or not
  anything has written that down, which makes the sweeper an optimisation rather than a requirement.
- `Till`: the load-decide-apply loop, with optimistic concurrency and no backoff.
- `InMemoryLedger`: the whole thing with no database, for embedding and for the simulator.

### Storage

- `JdbcLedger`: PostgreSQL, with a read-only repeatable-read read and a read-committed write carrying
  expected versions.
- A transactional outbox, delivered at least once, with deduplication keys that are a function of
  what happened.
- A `check` constraint that makes an oversell impossible at the database level.

### Testing

- `till-testkit`: a deterministic concurrency simulator that interleaves callers a phase at a time
  and injects crashes, lost answers, clock jumps and abandoned holds — all from one seed.
- Seven invariants checked after **every step**, including that the ledger agrees with its own outbox.
- A history check for the things invariants structurally cannot see: one key, one answer; a promised
  hold exists; nothing left the building unannounced.
- Four known flaws that the suite is asserted to catch, naming which check catches each.
- A differential test: the same seeded schedule against both ledgers, compared row for row.

### The service

- REST with OpenAPI, RFC 9457 problem bodies carrying `code` and `shortfalls`.
- Two bearer tokens, and a refusal to start unauthenticated on a reachable address.
- Prometheus metrics tagged by outcome rather than by status code, health probes on a separate port.
- A background sweeper and outbox publisher, both safe on every instance at once.

### The client

- `TillClient`, on the JDK's HTTP client, with no serialisation dependency. Retries a 503 and a
  dropped connection with the **same** idempotency key.
- `tillctl`, as a single runnable jar.

### The browser console

- `till-web`: React, TypeScript and Vite, served by the service from inside its own jar.
- `/shop` — a shop front with a hold, a live countdown and a Pay button. Two tabs racing for the last
  unit is the demonstration the project exists for.
- `/ops` — the ledger: totals, the stock split per SKU, reservations with both their stored and their
  effective state, and the outbox backlog.
- TypeScript types generated from a committed `openapi.json`, with a server test that regenerates it
  and fails when the committed copy has gone stale.
- Bounded listing endpoints behind it: `GET /v1/stock`, `GET /v1/reservations`, `GET /v1/outbox`.
- Built by an opt-in `-Pweb` Maven profile; a plain build has no console and says so at startup.

### Bugs found before release

Listed in the README, with what caught each.

[Unreleased]: https://github.com/jason-te-sde/till/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jason-te-sde/till/releases/tag/v0.1.0
