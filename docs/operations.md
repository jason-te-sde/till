# Running till

Written for somebody who has been paged.

## The shape of it

Two stateless services behind an edge proxy, each with a PostgreSQL database of its own, and a broker
between them. Every instance of each service is interchangeable; there is no leader, no coordination
and no sticky routing — sessions live in Redis, not in a process.

```
browsers ──▶ edge (nginx) ──▶ till-store (n) ──▶ till (n) ──▶ PostgreSQL (till)
                 │                 │  │                         │
                 │                 │  └──▶ PostgreSQL (store), Redis (sessions)
                 │                 │                            │
                 │                 └──◀── Kafka ◀── the outbox ─┘
                 │
                 └── sign-in redirects ──▶ identity provider (Cognito; Keycloak locally)
```

`till` is the ledger: it owns stock and decides every sale. `till-store` owns the catalogue, orders,
sign-in and the operator API, and reaches the ledger only through its public API with a client token.
The edge serves the storefront's files and is the only thing a browser talks to.

Scale either service by adding processes until its database is the limit. For the ledger that will
be the case first, because every command is two short transactions against a handful of rows.

On AWS this is [`infra/`](../infra/README.md): Fargate, RDS, ElastiCache, Cognito and CloudFront,
with the settings below filled in, and a script that starts it, checks it and stops it.

## Starting the ledger

```bash
java -jar till-server.jar \
  --spring.datasource.url=jdbc:postgresql://db:5432/till \
  --spring.datasource.username=till \
  --spring.datasource.password=... \
  --till.auth.client-token=... \
  --till.auth.admin-token=...
```

Everything can also be set with `TILL_`-prefixed environment variables or a properties file passed
with `--spring.config.additional-location`.

**It refuses to start unauthenticated on an address other people can reach.** An inventory ledger
with no token is a service where anyone who can open a socket can reserve every unit you have. Set a
token, bind `server.address` to loopback, or pass `--till.insecure=true` — which is named that way so
that it cannot end up in a production configuration without somebody having read it.

The schema is applied by Flyway at startup from `classpath:db/migration`, which ships inside
`till-jdbc`, so the code and the schema it expects are versioned together.

## The two tokens

| | |
| --- | --- |
| `till.auth.client-token` | every endpoint under `/v1` |
| `till.auth.admin-token` | additionally required by `POST /v1/stock/{sku}/adjust` |

Two rather than one because ejecting stock from the ledger is not the same privilege as taking a hold
on it. A checkout service needs the first and should never hold the second: if it is compromised, the
damage is a day of fraudulent orders rather than an inventory that can be zeroed.

If only the client token is set, it also allows adjustments, and the service says so at startup.

The store holds both, and the difference is where each is used: checkout reserves, commits and
releases with the client token; the admin token is used only behind `/api/ops`, which answers only
members of the identity provider's admin group. Give the store the admin token only if operators
should be able to adjust stock from the console.

## Ports

| Service | API | Management |
| --- | --- | --- |
| `till` | `8080` — the ledger's API | `9101` |
| `till-store` | `8081` — the store's API, reached through the edge | `9102` |
| edge | `8080` in its container — the only port a browser needs | `/healthz` on the same port |

In the compose stack the edge is published on `8080` and the ledger on `127.0.0.1:8090`, for
debugging; nothing publishes the store's API port, because nothing outside should call it directly.

The management ports are separate so that metrics and probes are not reachable from wherever the API
is, and so the API's authentication does not have to carve out exceptions for them. **Do not expose
9101 or 9102.**

Setting `management.server.port` to the same value as `server.port` puts them back on one port, where
anything that can reach the API can also read the metrics and the environment. Till logs a warning at
startup when it sees that, and starts anyway — it is a reasonable thing to do on a laptop and a
mistake in production, and only you can tell which this is.

| | |
| --- | --- |
| `/actuator/health/readiness` | can this instance serve a request? Checks the database. Use this for the load balancer |
| `/actuator/health/liveness` | is this process wedged? Does **not** check the database |
| `/actuator/prometheus` | metrics |

Using readiness as a liveness probe is the classic mistake: a database hiccup would restart every
instance at once, which is the worst possible response to a database hiccup.

## What to alert on

| Metric | Alert when | Because |
| --- | --- | --- |
| `till_outbox_backlog` | growing for more than a few minutes | everything downstream is working from a picture of stock that is falling further behind. This is the single most important number here |
| `till_outcome_total{outcome="exhausted"}` | non-zero and rising | commands are being refused for contention, not for stock. Raise `till.max-attempts`, or look at why so many callers are on one SKU |
| `till_outcome_total{outcome="deadline_exceeded"}` | non-zero and rising | callers are giving up before the ledger can finish, not because the rows keep moving. Look at `till_command_seconds` and the database, not at contention — raising `till.max-attempts` does nothing for a caller whose own clock ran out |
| `till_late_total` | tracking `till_outcome_total{outcome="deadline_exceeded"}` closely | decisions are being applied after their caller's deadline, inside `apply` itself — a pooled connection (`spring.datasource.hikari.connection-timeout`) or a slow statement. If the two numbers move together, the pre-apply check in `Till.execute` is arriving too late to matter and an in-transaction check ([ADR 11](design/0011-request-deadlines.md)) is worth its cost |
| `till_outbox_failures_total` | rising | the broker is unreachable. Nothing is lost — the batch is offered again — but it is not being delivered |
| `till_sweeper_failures_total` | non-zero | the sweep is throwing. Stock still comes back on demand, so this is not urgent, but something is wrong |
| `till_retention_failures_total` | non-zero | the pruning pass is throwing, so three tables are growing. Not urgent on the hour it starts; very urgent on the month it continues |
| `till_auth_denied_total{reason}` | a spike, or a slow climb | `missing` and `unknown` mean callers without a usable token — usually a deploy that did not get the secret. `insufficient` means a client token reaching for an admin endpoint |
| `till_command_seconds` p99 | above a few tens of milliseconds | almost always the database, or contention producing retries. Published as a histogram, so this is a real quantile across instances rather than an average of per-instance quantiles, and `till_command_seconds_bucket` can be read against an SLO directly |
| `till_outcome_total{outcome="insufficient_stock"}` | however you like | this is a business metric, not a fault. It is what running out of stock looks like |

For the store, the built-in HTTP metrics on `9102` and the broker's own tooling cover it:

| Signal | Alert when | Because |
| --- | --- | --- |
| `http_server_requests_seconds_count{uri=~"/api/orders.*",status="503"}` | rising | the store cannot reach the ledger. Checkout is down; browsing is not |
| consumer lag on the `till-store` group (`kafka-consumer-groups.sh --describe --group till-store`) | growing | store pages are showing stock that is falling further behind the ledger. Nothing oversells — the ledger decides at checkout — but more customers are refused at the last step |

`till_outcome_total` is tagged by outcome rather than by status code on purpose: "how many
reservations were refused for want of stock" is a question about the business, and "how many POSTs
returned 409" is a question about the router.

### Following one request

Every request has an id. Send `X-Request-Id` and till uses it; send nothing and it mints one. It
comes back on the response, appears in every log line for that request, and is in the body of every
error — so a screenshot of a failure is enough to find the log lines, with nothing to correlate by
timestamp.

```
%5p [till,3f2a9c1e-...]   the logging pattern; the second field is the id
```

Ids you send are accepted only if they look like one: 8–64 characters of `A-Za-z0-9._-`. Anything
else is replaced rather than refused, because a caller with a strange id should still get an answer,
and an unvalidated value goes into log lines.

## Starting the store

```bash
java -jar till-store.jar
```

configured entirely by environment:

| Variable | What it is |
| --- | --- |
| `STORE_DB_URL`, `STORE_DB_USER`, `STORE_DB_PASSWORD` | the store's own database. Not the ledger's: the store must not be able to reach the ledger's tables |
| `TILL_URL`, `TILL_CLIENT_TOKEN` | the ledger, and the token checkout reserves with |
| `TILL_ADMIN_TOKEN` | optional; lets the operator console adjust stock |
| `STORE_REDIS_HOST`, `STORE_REDIS_PORT` | sessions, the tokens inside them, and the catalogue's cached answers |
| `STORE_CATALOGUE_CACHE` | `false` to answer every catalogue read from the database; see below. On by default |
| `STORE_KAFKA_BROKERS`, `TILL_KAFKA_TOPIC` | the ledger's events. Blank means do not consume: the store still serves, with availability frozen at whatever was last projected |
| `STORE_OIDC_ISSUER_URI`, `STORE_OIDC_CLIENT_ID`, `STORE_OIDC_CLIENT_SECRET` | the identity provider. Required: the store refuses to start without one |
| `STORE_OIDC_LOGOUT_URI` | where signing out ends the provider's session too; see below |
| `STORE_COOKIE_SECURE` | `true` everywhere but a plain-HTTP laptop |
| `STORE_HOLD_FOR` | how long a placed order holds its stock; 15 minutes |
| `STORE_DEMO_SEED_STOCK` | stock every game on start. The compose stack sets it; a real store must not |

The schema is applied by Flyway at startup, like the ledger's.

## Sign-in

The store runs the OpenID Connect authorization-code flow with PKCE itself, and keeps the tokens in
the session. The browser gets an `HttpOnly`, `SameSite=Lax` session cookie and never sees a token.

**Amazon Cognito.** Create a user pool with an app client that has a secret, and a group called
`admins` for operators. Then:

| | |
| --- | --- |
| `STORE_OIDC_ISSUER_URI` | `https://cognito-idp.<region>.amazonaws.com/<pool-id>` — everything else is discovered from it |
| Allowed callback URL | `https://<your-host>/login/oauth2/code/idp` |
| Allowed sign-out URL | `https://<your-host>/` |
| `STORE_OIDC_LOGOUT_URI` | `https://<domain>.auth.<region>.amazoncognito.com/logout?client_id={clientId}&logout_uri={baseUrl}/` |
| OAuth scopes | `openid`, `profile`, `email` |

Group membership arrives in the ID token as `cognito:groups`, and members of `admins` get the operator
console. Both names are settings (`store.auth.groups-claim`, `store.auth.admin-group`) for providers
that spell them differently.

**Keycloak, locally.** The compose stack imports a realm with the client, two demonstration accounts
and the `admins` group, and a mapper that names the groups claim the way Cognito does — so the store
runs the same code against both. It sets the provider's endpoints explicitly
(`STORE_OIDC_AUTHORIZATION_URI`, `..._TOKEN_URI`, `..._JWK_SET_URI`) because the browser reaches
Keycloak at `localhost:8180` and the store reaches it at `keycloak:8080`, and one discovery document
cannot be right for both. The issuer is still checked against every ID token; it is just not fetched.

**Behind TLS.** The redirect URI is built from the request, and behind a proxy from its
`X-Forwarded-*` headers — so the proxy in front of the store must set them, and whatever terminates
TLS must pass `X-Forwarded-Proto: https` on. Get this wrong and the provider refuses the sign-in with a
redirect-URI mismatch, which is the first thing to check when signing in fails.

**The catalogue is cached in Redis too**, behind the edge's five-second cache: an answer the
database gave is shared by every instance for as long as it can be trusted. A game, the featured and
newest rows and the genres for ten minutes (`store.catalogue.cache.stable`); best sellers and
related games, which move with sales, for a minute (`.sales`); searches, the long tail, for a minute
(`.searches`); each expiry moved by up to a tenth either way (`.jitter`) so that answers written
together do not expire together. Keys carry the schema version, so a release that changes the
catalogue never reads the last one's answers. Availability is never cached, and an order is always
priced from the database. If Redis is unreachable, catalogue reads go to the database and the store
says so in its log once a minute; browsing gets slower, not broken. Every minute each store logs a
`catalogue-reads` line — how many reads came from each source and how long they took — and
`store_catalogue_reads_seconds` has the same on its Prometheus endpoint.

**Sessions are in Redis**, so any instance can serve any request. The store uses Spring Session's
non-indexed repository, which needs no keyspace notifications — so it works on ElastiCache, which does
not allow the `CONFIG SET` the indexed one would issue. If Redis goes away, signed-in customers get
errors until it is back and visitors can still browse; restarting it signs everybody out and loses
nothing else.

## The edge

nginx, configured by `docker/edge/`. It serves the storefront's files and proxies `/api`, `/oauth2`,
`/login/oauth2`, `/v3/api-docs` and `/swagger-ui` to the store. Nothing of the ledger is reachable
through it.

| | |
| --- | --- |
| Security headers | set here, for everything, including a Content-Security-Policy that forbids inline script |
| Rate limits | per client address: 30 reads a second with a burst of 60, 5 writes a second with a burst of 10, 2 sign-in starts a second. A refusal is a `429` problem with `code: RATE_LIMITED` and a `Retry-After` |
| Catalogue cache | `/api/home`, `/api/games` and `/api/genres`, for as long as the store says: five seconds, stale for up to thirty while refetching, and up to five minutes while the store is down. `X-Cache-Status` on every response says which |
| Store down | a `503` problem with `code: STORE_UNAVAILABLE`, rather than nginx's own page |
| Health | `/healthz` |

The configuration is a template the image renders when it starts, from four variables:

| Variable | Default (the compose stack) | In AWS |
| --- | --- | --- |
| `STORE_UPSTREAM` | `store:8081` | the store's service-discovery name |
| `EDGE_RESOLVER` | `127.0.0.11`, Docker's DNS | the VPC's resolver. The store's address is re-resolved every ten seconds, so a replaced store task is found without restarting the edge |
| `EDGE_TRUSTED_PROXY` | `unix:` — trust nobody | the VPC's range, where the load balancer lives |
| `EDGE_ACCESS_LOG` | `/var/log/nginx/access.log edge` | the same, or `off` for a load test |

Behind a load balancer every connection comes from the balancer, and a rate limit keyed on that
address is one limit shared by every customer. For a connection from `EDGE_TRUSTED_PROXY`, the edge
takes the client's address from `CloudFront-Viewer-Address` instead; from anywhere else it ignores
the header, so a client cannot choose its own limit by sending one. The scheme comes from
`CloudFront-Forwarded-Proto`: CloudFront reaches the balancer over plain HTTP, and the store builds
the sign-in redirect from the scheme it is told, which the identity provider accepts only as `https`.
A forged one misdirects nobody's sign-in but the forger's.

## Tuning

| Setting | Default | What raising it costs |
| --- | --- | --- |
| `till.max-attempts` | 8 | tail latency under contention, in exchange for fewer 503s |
| `till.reclaim-limit` | 32 | how many expired holds a command short of stock writes off before deciding again. A command with stock to spare writes off none; `0` leaves them all to the sweeper |
| `till.default-ttl` | 15m | nothing technical; a longer hold is a customer holding stock somebody else would have bought |
| `till.max-ttl` | 24h | the same, with less of a bound |
| `till.sweeper.interval` | 5s | almost nothing. The sweep is cheap and indexed |
| `till.sweeper.batch` | 200 | a longer transaction per sweep, and more conflicts with live traffic |
| `till.sweeper.passes` | 10 | batches a run while each comes back full. The sweeper is what writes off abandoned holds now, so it has to keep up: 2,000 every five seconds here |
| `till.outbox.interval` | 1s | how stale the downstream view is allowed to be once the publisher has caught up; until then it does not wait |
| `till.outbox.batch` | 500 | a larger batch to redeliver when a publish fails |
| `till.outbox.passes` | 20 | batches a run publishes while each comes back full. One instance publishes at a time; the others count `till_outbox_standby_total` and take over when it stops |
| `till.kafka.partitions` | 12 | how many of the store's consumers can read the topic at once. The ledger declares the topic before it publishes, creating or growing it to this; growing it moves some keys to new partitions, which the store's projection tolerates |
| `till.kafka.replication-factor` | 1 | copies of each partition of a topic the ledger creates. Three on a cluster that should survive losing a broker; an existing topic's is only reported, because changing it is a reassignment |
| `spring.datasource.hikari.maximum-pool-size` | 16 | PostgreSQL sessions. The useful shape of overload is a queue in front of the pool, not a thousand sessions fighting over the same rows |
| `spring.datasource.hikari.connection-timeout` | 2000 ms | how long a command may queue for a connection before it is refused. Longer than the caller waits (the store: five seconds) is work done for nobody — in a load test, holds nobody would pay for |

**Contention on a single SKU is the case worth thinking about.** A thousand callers on one row will
produce retries; that is the design working, and the levers are `max-attempts` (how long a caller
will try) and a bounded pool (how many can try at once). When a SKU needs more than one row's worth
of write throughput — a launch, a flash sale — **split it** before the sale:

```bash
tillctl shard <sku> 16 --key=split-<sku>     # or POST /v1/stock/<sku>/shards {"shards": 16}, admin token
```

Its stock is then kept in sixteen rows, a hold takes its units from the row its id points at, and two
holds contend only when they land in the same one; every answer is still the SKU's, so a hold is
refused only when the whole SKU is short ([ADR 9](design/0009-hot-sku-shards.md)). In the contention
benchmark sixteen rows took the decisions that conflicted from 54% to 7%. Two costs: every command on
the SKU reads all of its rows, and rows are only ever added — so split what will be busy, not
everything. Splitting writes every row the SKU has, which is why it belongs before the sale rather
than in the middle of it.

## Retention

Three tables grow for as long as the service runs. Till prunes them itself, on a schedule, because
the alternative is a paragraph here telling you to write a cron job — which works right up until the
person who read it changes team, and then a disk fills at three in the morning over rows that stopped
mattering months ago.

| Setting | Default | Meaning |
| --- | --- | --- |
| `till.retention.enabled` | `true` | set `false` to do it yourself |
| `till.retention.interval` | `1h` | how often a pass runs |
| `till.retention.idempotency` | `7d` | **the dangerous one** — see below |
| `till.retention.outbox` | `30d` | published rows only; unpublished ones are never deleted, at any age |
| `till.retention.reservations` | `0s` | **zero means keep forever**, which is the default |
| `till.retention.batch` | `1000` | rows per statement |
| `till.retention.passes` | `20` | batches per table per pass, so a first run against years of history is bounded and comes back for the rest |

**`till.retention.idempotency` is the one to think about.** Deleting a record means a client retrying
that command **executes it again** rather than getting its original answer back. Seven days is far
longer than any sensible client retry window; it is the setting to raise, not lower. What the right
value is depends on your callers, not on till.

**For adjustments the effective window is the larger of `idempotency` and `outbox`.** An adjustment's
event is named `adjusted:<idempotency-key>` — an adjustment has no identity of its own to name it
after — so that name is unique only while the record exists. Till therefore refuses to forget a key
whose event is still queued, and errs long, in the safe direction, by construction.

Deletes are **bounded batches, repeatedly**, not one statement per table: a single delete removing a
month of rows holds a lock long enough for everything else to notice, and an interrupted run has
still made progress. Running it on every instance at once is safe — the deletes are idempotent, and
two of them racing produce one deletion and one that finds nothing.

A finished reservation is deleted with its lines, by cascade. A **`HELD`** one is never deleted at
any age and cannot be asked for: its units are counted in `till_stock.reserved`, and removing the row
without lowering that counter leaks the stock permanently.

| Metric | |
| --- | --- |
| `till_retention_deleted_total{table}` | rows removed, per table |
| `till_retention_failures_total` | a pass threw. Non-urgent — nothing is wrong with the data — but it means the tables are growing |

If you would rather do it yourself, turn it off and run the equivalent:

```sql
delete from till_outbox      where published_at is not null and published_at < now() - interval '30 days';
delete from till_idempotency where recorded_at  < now() - interval '7 days'
                               and not exists (select 1 from till_outbox o
                                               where o.dedupe_key = 'adjusted:' || till_idempotency.idem_key);
delete from till_reservation where state <> 'HELD' and expires_at < now() - interval '30 days';
```

Outbox first, so that one pass can do both once both windows have elapsed.

**The store's inbox is not pruned yet.** `store_consumed_event` gains one row per event the store
applies. Its safe window is the topic's retention: forget a deduplication key while the broker can
still redeliver that event, and the redelivery is applied twice. Until the store prunes it, delete by
hand no more recently than the topic's `retention.ms`:

```sql
delete from store_consumed_event where consumed_at < now() - interval '30 days';  -- > the topic's retention
```

Orders are kept forever, deliberately: they are a customer's purchase history.

## Sizing

Per row, roughly: a stock row is tens of bytes, a reservation is about a hundred plus fifty per line,
an idempotency record is about two hundred, and an outbox row is about two hundred. A service doing
a hundred reservations a second with two lines each, kept for a day, is on the order of a few
gigabytes a day before indexes. Measure yours; this is an order of magnitude, not a promise.

## Backup and restore

Nothing special. `pg_dump` and `pg_restore`, or whatever your platform does, with two notes:

- **Restoring to a point in time is consistent.** Every invariant till maintains is maintained inside
  single transactions, so any committed snapshot of the database is a valid state — there is no
  separate log to reconcile and no repair step.
- **The outbox is part of the backup.** Restoring an older snapshot restores unpublished events that
  may already have been delivered. Consumers deduplicate on `dedupe_key`, which is what makes that
  survivable, so check that yours actually does before you need to.
- **The store's database is backed up separately**, and restoring it to an earlier point than the
  ledger's is safe in one direction only: its projection will be behind, and catches up from the
  topic. Orders placed after the restore point are gone from the store while their holds remain in
  the ledger, where they expire on their own.
- **Redis needs no backup.** It holds sessions; losing it signs everybody out.

## Upgrades

Rolling. Instances are stateless and interchangeable, and two versions can run at once as long as
they agree about the schema — which is what the Flyway version records.

A migration that only adds things is safe during a rolling deploy. One that removes or narrows
something is not, and should be split: deploy code that no longer needs the column, then remove it.
There is one migration so far, so this is advice rather than experience.

## Symptom to cause

| Symptom | Look at |
| --- | --- |
| 503s with `"code":"CONTENTION"` | contention, not an outage. `till_outcome_total{outcome="exhausted"}`, then how many callers are on one SKU. Split that SKU (`tillctl shard <sku> 16`), or raise `till.max-attempts` |
| 503s with `"code":"DEADLINE_EXCEEDED"` | the caller's own deadline, not the rows: this fires even on a command's very first attempt, which contention cannot. Compare `till_outcome_total{outcome="deadline_exceeded"}` with `{outcome="exhausted"}` — rising together points at an overloaded database slowing everything down; `deadline_exceeded` alone with `exhausted` flat points at callers with too short a budget for how long the ledger legitimately takes. Splitting a SKU or raising `till.max-attempts` helps the second symptom, not the first |
| 503 `"Ledger unavailable"` | the database. The readiness probe will already be failing |
| 422 `IDEMPOTENCY_KEY_REUSED` | a client is reusing a key for a different body. Usually a key derived from something not unique per request — a cart id rather than a checkout attempt |
| `available` lower than it should be | expired holds not yet written off. Check `till_sweeper_failures_total`; the next command short of stock on those SKUs will reclaim them anyway |
| Refuses to start, "refusing to listen on" | no token and a reachable address. Set `till.auth.client-token` |
| Signing in lands back on the store with "Sign-in didn't complete" | the provider refused. In order: the redirect URI registered at the provider matches `https://<host>/login/oauth2/code/idp` exactly; the proxy passes `X-Forwarded-Proto` and `-Host`; the issuer the store is configured with is the one in the tokens; the store's clock is right |
| 403 with `"code":"CSRF"` | a write without a matching `X-XSRF-TOKEN`. Almost always a tab left open across a session that has since ended; a reload fixes it |
| 429 with `"code":"RATE_LIMITED"` | the edge's limits. Behind a load balancer, check nginx is seeing client addresses rather than the balancer's |
| 503 with `"code":"STORE_UNAVAILABLE"` | the edge cannot reach the store |
| 503 with `"code":"LEDGER_UNAVAILABLE"` | the store cannot reach the ledger. The request is safe to retry: every write carries an idempotency key |
| Store pages show stale stock | the consumer. Is `STORE_KAFKA_BROKERS` set, is the broker up, and is the `till-store` group's lag falling? |
| Everybody was signed out at once | Redis restarted, or lost its data |
| The store refuses to start: "an identity provider" | `STORE_OIDC_ISSUER_URI` and `STORE_OIDC_CLIENT_ID` are required |
| Refuses to start, Flyway validation | the database has a schema this build did not create, or a migration was edited after being applied |
| A store or ledger deploy rolls back with `remaining connection slots are reserved` | the connection budget, not the new build. Steady state holds 64 against the `db.t4g.micro`'s ~70; `infra/runtime/services.tf` replaces one task at a time so a deploy never adds to it |
| `LedgerException` about an impossible stock level | a bug in till. The database refused a level the application should not have been able to produce. The stock row named in the message is the place to start, and it has **not** been corrupted — the transaction rolled back |
| The outbox backlog grows and nothing errors | the publisher is not running. `till.outbox.enabled`, and whether the scheduler is alive. The gauge is read straight from the table, so it is right even when the publisher is the thing that is broken. If `till_outbox_standby_total` is rising on every instance, one of them holds the publishing claim and is stuck: its session in `pg_locks`, advisory lock `0x74696c6c6f757462` |
| The store's stock lags while the outbox is empty | the consumers. How many partitions the topic has (`kafka-topics.sh --describe`): one partition is one consumer, however many stores are running |
| `till_idempotency` keeps growing past its window | rows are only forgotten once the matching outbox event has gone. Check `till.retention.outbox` and whether the publisher is draining |
| A caller reports an error you cannot find | ask for the `requestId` in the body. It is in every log line for that request |

## What till does not do

Stated here rather than discovered: no real payment (paying commits the hold and moves no money), no
multi-tenancy, no reservation of a quantity range or a specific serial number, no partial fulfilment of
a multi-line hold, no backorders, no scheduled availability, no read replicas, and no encryption of
data at rest beyond whatever the disk does. [`SECURITY.md`](../SECURITY.md) is explicit about the security half of that
list and [`design/0005-scope.md`](design/0005-scope.md) about the rest.
