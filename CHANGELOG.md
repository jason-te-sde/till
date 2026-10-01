# Changelog

Notable changes, newest first. This project follows [semantic versioning](https://semver.org) from
1.0 onwards; until then, a minor bump means a wire format, a schema, or the `Ledger` contract
changed, and a patch means everything else.

The public surface is `Ledger`, `Kernel`, `Command`, `Outcome`, and the HTTP API. `till-testkit` is
explicitly not: it is a testing tool and it will change.

## [Unreleased]

### Added

- **Monthly partitions for the sales roll-up** ([ADR 10](docs/design/0010-sales-partitions.md)).
  `store_sales_daily` is now range-partitioned by `day` — one partition a month, plus a `default`
  catch-all — so a seven-day best-seller read touches one or two partitions instead of the whole
  table, and retiring a month is a dropped table instead of a delete sized to however many rows it
  held. `V7__sales_partitions.sql` converts the existing table without losing a row.
  `SalesPartitionMaintenance` creates this month's and next month's partitions on start-up and once a
  day, moves rows out of `default` when a month's partition is created after they landed there, and
  drops whole months past `store.sales.retention` (default 13 months; refused below the seven-day
  best-seller window it would otherwise cut a partition out from under). Every store instance running
  this at the same moment on a fresh deploy is claimed with a transaction-scoped advisory lock, the
  same pattern as the ledger's outbox publisher, and the whole pass runs in one transaction bounded by
  `store.sales.lock-timeout` (default 2s), so a task that dies mid-pass never leaves a partition
  created but not attached, and a slow query already holding the table's lock costs this job one
  retry rather than every other reader and writer queuing behind it.
- **The ledger honours the caller's deadline** ([ADR 11](docs/design/0011-request-deadlines.md)).
  `TillClient` sends, on every attempt, what is left of its call's deadline as `Till-Timeout-Ms` —
  milliseconds, relative rather than an absolute instant, so clock skew between hosts cannot matter.
  `Till.execute(Command, Instant)` checks it before loading and again before applying, and never
  applies a decision for a caller who has stopped waiting; a passed deadline is a new
  `DeadlineExceededException`, answered 503 `DEADLINE_EXCEEDED` and counted on its own
  (`till.outcome{outcome="deadline_exceeded"}`), next to `exhausted` so an operator can tell overload
  from contention. What it does not close — a decision applied after the deadline because `apply`
  itself waited, for a pooled connection or a statement — is measured instead, as `till.late`, rather
  than checked inside `JdbcLedger`'s write transaction on every command. The last load test found
  41,259 of 48,744 holds still held when the run ended: checkouts the store had already stopped
  waiting for while the ledger, behind the database, kept working on them.
- **Kafka runs three brokers, not one** ([ADR 13](docs/design/0013-kafka-replication.md)):
  replication factor 3 and `min.insync.replicas` 2 on every topic, including the one the ledger
  declares, so an acknowledged write survives losing any single broker and a second one down is
  refused rather than silently accepted. `infra/runtime/services.tf` runs them as three ECS
  services (`kafka-1`..`kafka-3`), each a stable Cloud Map identity and a shared `CLUSTER_ID`, with
  the matching security group rules for the controller quorum; `docker-compose.kafka-cluster.yml`
  is the local equivalent, exercised by a new CI job ("the Kafka cluster survives losing a broker")
  that stops one broker and confirms delivery continues, then a second and confirms it does not.
  Not yet applied to the AWS account. `infra/README.md`'s cost tables and `scripts/aws.sh`'s
  estimate move from $0.16 to $0.22 an hour accordingly.
- **Aurora PostgreSQL Serverless v2 as the database, picked per deployment**
  (`infra/variables.tf`'s `database`, `infra/runtime/state.tf`, `scripts/aws.sh up
  --database=aurora`): the free plan's other option to RDS, capped at 4 ACU and 1 GiB of storage per
  cluster and able to pause at 0 ACU between connections, where RDS is billed whether or not a
  connection is open. RDS stays the default and is unchanged; `scripts/aws.sh loadtest` records
  which one a run used and, for Aurora, `ServerlessDatabaseCapacity` alongside the database's CPU.
- **A hot SKU's stock in several rows** ([ADR 9](docs/design/0009-hot-sku-shards.md)).
  `Command.Shard` — `POST /v1/stock/{sku}/shards`, `TillClient.shard`, `tillctl shard` — splits a SKU
  across up to 64 rows. A hold takes its units from the row its reservation id points at, and from
  several only when no one row has them all; commit, release and expiry give them back there; the
  answers stay the SKU's. Two holds on one SKU contend only in the same row: in the contention
  benchmark, sixteen rows took the decisions that conflicted from 54% to 7%.
  `V3__stock_shards.sql` keys stock and lines by shard; every stock view reports `shards`; the store
  splits its demonstration stock when `store.demo.shards` asks, and the load test splits sixteen ways.
- **A deadline on the client.** `TillClient.Builder.deadline` bounds a call, retries and backoffs
  included; the store's is five seconds (`store.till.deadline`). Before it, a ledger too busy to
  answer cost a checkout four five-second attempts — the 20.8 s p99 of the first load tests.
- **A contention benchmark** for the ledger's write path (`ContentionBenchmark` in `till-jdbc`, only
  with `-Dtill.benchmark=true`): conflicts, snapshots and transactions per completed checkout.
- **A deployment on AWS** (`infra/`, `scripts/aws.sh`): ECS on Fargate, RDS PostgreSQL, ElastiCache
  Valkey, Cognito, and CloudFront to an internal load balancer through a VPC origin; `up`, `smoke`,
  `down`, and a schedule inside AWS that scales every service to zero at a deadline, for a session
  that outlives whoever started it.
- **A load test** (`till-loadtest`, [`docs/load-test.md`](docs/load-test.md)): a written protocol —
  8,000 concurrent shoppers, 3,000 requests a second, a p99 under a second — run by k6 shoppers with
  their own cookies, addresses and sign-ins through a stand-in OpenID provider; each result carries
  CloudWatch's and PostgreSQL's account of the same window.
- **The catalogue's read cache**, in Valkey: a lifetime per kind of answer, with jitter; one load per
  miss; keys versioned by the schema. Measured under the load test: the average catalogue read from
  397.67 ms to 34.42 ms.

- **A game store in front of the ledger.** `till-store` is now a backend-for-frontend: a catalogue of
  thirty-two games with PostgreSQL full-text search, facets and sorting; orders placed by holding
  stock in the ledger, priced on the server, paid and cancelled through commit and release, and
  reconciled from the event stream; and the operator console's API behind `/api/ops`, for the
  identity provider's `admins` group.
- **Sign-in with OpenID Connect, run on the server.** Authorization code with PKCE, tokens kept in the
  session in Redis, an `HttpOnly` `SameSite=Lax` cookie in the browser, and CSRF protection by
  cookie-to-header double submit. Amazon Cognito in production; Keycloak locally, with the same
  groups claim, so both run one code path.
- **The storefront.** `till-web` rebuilt as a store on React, Redux Toolkit and RTK Query: a home page
  with featured games and shelves, browse and search with every filter in the URL, a page per game
  with live stock, a cart that survives reloads and the trip to the identity provider, a two-step
  checkout with a visible hold, order history, and the operator console for admins. Cover art is
  painted per game from twelve SVG motifs seeded by the SKU. Dark and light themes, responsive, and
  keyboard- and screen-reader-friendly.
- **An edge proxy.** nginx serves the storefront and is the only public entry point: security headers
  and a Content-Security-Policy without inline script, per-address rate limits answered as problems,
  and a five-second catalogue microcache whose staleness the store bounds.
- **One error shape.** Every failure from the store — the ledger's refusals, validation, the security
  layer, the framework's own errors and an unexpected exception — is an RFC 9457 problem with a
  `code`, declared in `openapi/store.json` and checked against real failures of each kind.
- The compose stack runs the whole platform with health checks — edge, store, ledger, Kafka,
  PostgreSQL, Redis and Keycloak — and CI builds every image, drives the store in a browser against
  it, and checks the edge's headers, cache and rate limits.

- **`till-kafka`** — publishes the outbox to Kafka with plain `kafka-clients`. `acks=all` and
  producer idempotence; records keyed by the entity the event is about, so one reservation's
  lifecycle cannot arrive out of order; deduplication key, outbox sequence, type and decision
  instant in headers. A failed batch throws, so the whole batch is offered again.
- **`till-catalogue`** (now `till-store`) — the storefront's first version. Owns games, prices and a read model of availability built
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

### Changed

- **A snapshot that reclaims nothing is one statement**, in autocommit, with no `SET`, `BEGIN` or
  `COMMIT` ([ADR 12](docs/design/0012-one-statement-snapshot.md)) — the lean first decision of every
  reserve, commit and release, which is the common case by far. A load test found the `SET
  TRANSACTION` this replaces responsible for 10% of the database's statement time
  ([docs/load-test.md](docs/load-test.md)); PostgreSQL already gives one statement one instant, so
  the common load needs no transaction to say so, and only a load that reclaims expired holds, or the
  sweep, still opens one. The contention benchmark (`-Dbench.dbcpus=2 -Dbench.shards=16
  -Dbench.seconds=30`, three interleaved runs a side against the commit this branched from) did not
  show a measurable throughput change on a laptop — 930 checkouts/s before, 951 after, both inside
  the ±25% run-to-run spread this benchmark already has — which this records plainly rather than
  rounding up: the round-trip count is halved regardless (proved directly by watching the connection),
  but this local benchmark's bottleneck is mostly the apply path's conflicts, which this does not
  touch. The next AWS load test is what should actually show it.
- **The event stream keeps up.** The outbox publisher drains until it has caught up
  (`till.outbox.passes`), one instance at a time under a publishing claim, and the ledger declares its
  topic with `till.kafka.partitions` (12) before it publishes. One batch of 200 a second had capped the
  stream at about 200 events a second, a second instance added only copies, and a topic left to the
  broker's default had one partition — so of four stores, one consumed. Measured end to end, from about
  390 events a second to about 3,000 (ADR 6). A store now waits for the ledger to declare the topic
  rather than letting the broker create it with one partition.
- **A decision writes its stock rows first**, in SKU and shard order: they are the rows most likely to
  have moved, and a conflict found after the reservation's inserts rolled them back.
- **A snapshot's transaction sets its own isolation** with `SET TRANSACTION`, rather than the
  connection being asked, set and put back around it — three statements a load, each a transaction.
- **Expired holds are written off when a command is short of stock**, not by every command; the
  sweeper drains in passes (`till.sweeper.passes`). Both pools refuse after two seconds waiting for a
  connection.
- `StockItem` gains `shards`, `Reservation` gains `allocations`, `Mutation.PutStock` and `Snapshot` name
  shards, and `LedgerInspector` gains `allShards` — a schema, a wire format and the `Ledger` contract,
  so a minor version when released.

- `till-catalogue` is now `till-store`, and its tables are prefixed `store_` (`V3__store_prefix.sql`).
- The ledger no longer serves a browser console. The `-Pweb` profile, `WebUi` and
  `till.web.cors-origins` are gone; the storefront is served by the edge, and talks only to the store.
- The ledger's published contract moved from `till-web/openapi.json` to `openapi/ledger.json`, beside
  the store's.

### Fixed

- **A store or ledger deploy could roll back with `remaining connection slots are reserved`.** The
  store and the ledger hold 64 database connections between them at steady state, and a
  `db.t4g.micro` refuses new ones at around 70. The default ECS deployment (`maximumPercent` 200)
  started a full set of replacement tasks before stopping the old ones, so a rolling deploy of either
  service briefly doubled its share and tipped the total over — twice, on 2026-10-01, for a `store`
  deploy that only changed `STORE_DEMO_SHARDS`. Both services now replace one task at a time
  (`deployment_maximum_percent = 100`), which holds the total at steady state through a deploy.
- **One customer's retry could be answered with another customer's hold.** The storefront passed
  browsers' idempotency keys straight into the ledger's global key space, so two customers sending the
  same string would have had the second handed the first one's reservation. Keys are now a digest of
  purpose, customer and key.
- **The session cookie was not `HttpOnly` outside Spring Boot's embedded server.** Boot applies
  `server.servlet.session.cookie.*` to Spring Session's cookie only there; in any other deployment
  shape it copies the container's defaults. The flags are set in code now.
- **A cached catalogue answer could be served indefinitely.** The edge was told to serve stale while
  refreshing, with no upper bound, and refreshes carrying a CSRF cookie could never be stored. Public
  responses set no cookie now, and the store bounds staleness itself.
- **An idempotency key the store accepted could not be stored**: 200 characters allowed, 128 in the
  column. The limit is 128 everywhere now.
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
