# Running till

Written for somebody who has been paged.

## The shape of it

One stateless process, one PostgreSQL database. Every instance is interchangeable; there is no
leader, no coordination, and no sticky routing. Scale by adding processes until the database is the
limit, which it will be, because every command is two short transactions against a handful of rows.

```
browsers ─┐
          ├─▶ load balancer ──▶ till (n instances) ──▶ PostgreSQL
clients ──┘                          │
                                     └──▶ your broker, via the outbox publisher
```

The browser console is inside the jar and served from the same origin as the API, so there is nothing
extra to deploy and no CORS to configure.

## Starting it

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

## Ports

| | |
| --- | --- |
| `8080` | the API, and the browser console. `server.port` |
| `9101` | health, metrics, info. `management.server.port` |

They are separate so that metrics and probes are not reachable from wherever the API is, and so the
API's authentication does not have to carve out exceptions for them. **Do not expose 9101.**

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
| `till_outbox_failures_total` | rising | the broker is unreachable. Nothing is lost — the batch is offered again — but it is not being delivered |
| `till_sweeper_failures_total` | non-zero | the sweep is throwing. Stock still comes back on demand, so this is not urgent, but something is wrong |
| `till_retention_failures_total` | non-zero | the pruning pass is throwing, so three tables are growing. Not urgent on the hour it starts; very urgent on the month it continues |
| `till_auth_denied_total{reason}` | a spike, or a slow climb | `missing` and `unknown` mean callers without a usable token — usually a deploy that did not get the secret. `insufficient` means a client token reaching for an admin endpoint |
| `till_command_seconds` p99 | above a few tens of milliseconds | almost always the database, or contention producing retries. Published as a histogram, so this is a real quantile across instances rather than an average of per-instance quantiles, and `till_command_seconds_bucket` can be read against an SLO directly |
| `till_outcome_total{outcome="insufficient_stock"}` | however you like | this is a business metric, not a fault. It is what running out of stock looks like |

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

## The console

`/` is the shop front and `/ops` is the operator view. Both are served from inside the jar.

| | |
| --- | --- |
| It answers 404 | the jar was built without `-Pweb`. The startup log says which: *"no browser console in this build"* |
| It asks for a token | by design. Paste the client token for the shop, the admin token to see the outbox. It is kept in `sessionStorage`, so closing the tab forgets it |
| The outbox panel says it needs the admin token | also by design. The backlog is not a secret; the payloads are the whole history of what moved |
| You want to host it elsewhere | set `till.web.cors-origins` to the origins that may call the API. `*` is refused at startup |

**It is a console, not a customer-facing shop.** The shop route exists to demonstrate the reservation
path; it has a hard-coded catalogue and no payment. And the authentication is the honest minimum for
a console: a person pastes a bearer token. A product would sign somebody in, keep a session cookie
the page cannot read, and put a small server in front that holds the token —
[`design/0007-browser-console.md`](design/0007-browser-console.md) says why that server is out of
scope here. **Do not expose this console to the public with a real token in it.**

## Tuning

| Setting | Default | What raising it costs |
| --- | --- | --- |
| `till.max-attempts` | 8 | tail latency under contention, in exchange for fewer 503s |
| `till.reclaim-limit` | 32 | each decision touches more rows, so conflicts get likelier, in exchange for expired stock coming back sooner |
| `till.default-ttl` | 15m | nothing technical; a longer hold is a customer holding stock somebody else would have bought |
| `till.max-ttl` | 24h | the same, with less of a bound |
| `till.sweeper.interval` | 5s | almost nothing. The sweep is cheap and indexed |
| `till.sweeper.batch` | 200 | a longer transaction per sweep, and more conflicts with live traffic |
| `till.outbox.interval` | 1s | how stale the downstream view is allowed to be |
| `till.outbox.batch` | 200 | a larger batch to redeliver when a publish fails |
| `spring.datasource.hikari.maximum-pool-size` | 16 | PostgreSQL sessions. The useful shape of overload is a queue in front of the pool, not a thousand sessions fighting over the same rows |

**Contention on a single SKU is the case worth thinking about.** A thousand callers on one row will
produce retries; that is the design working, and the levers are `max-attempts` (how long a caller
will try) and a bounded pool (how many can try at once). If a flash sale genuinely needs more than
one row's worth of write throughput, the answer is to split the stock across several SKUs and let the
caller pick, which till supports by having no opinion about what a SKU means.

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

## Upgrades

Rolling. Instances are stateless and interchangeable, and two versions can run at once as long as
they agree about the schema — which is what the Flyway version records.

A migration that only adds things is safe during a rolling deploy. One that removes or narrows
something is not, and should be split: deploy code that no longer needs the column, then remove it.
There is one migration so far, so this is advice rather than experience.

## Symptom to cause

| Symptom | Look at |
| --- | --- |
| 503s with `"code":"CONTENTION"` | contention, not an outage. `till_outcome_total{outcome="exhausted"}`, then how many callers are on one SKU. Raise `till.max-attempts` |
| 503 `"Ledger unavailable"` | the database. The readiness probe will already be failing |
| 422 `IDEMPOTENCY_KEY_REUSED` | a client is reusing a key for a different body. Usually a key derived from something not unique per request — a cart id rather than a checkout attempt |
| `available` lower than it should be | expired holds not yet written off. Check `till_sweeper_failures_total`; the next command touching those SKUs will reclaim them anyway |
| Refuses to start, "refusing to listen on" | no token and a reachable address. Set `till.auth.client-token` |
| The console is a 404 | built without `-Pweb`. The startup log says so |
| The console loads but every call fails with 401 | the token in the tab is wrong. "Forget token" and paste it again |
| Refuses to start, Flyway validation | the database has a schema this build did not create, or a migration was edited after being applied |
| `LedgerException` about an impossible stock level | a bug in till. The database refused a level the application should not have been able to produce. The stock row named in the message is the place to start, and it has **not** been corrupted — the transaction rolled back |
| The outbox backlog grows and nothing errors | the publisher is not running. `till.outbox.enabled`, and whether the scheduler is alive. The gauge is read straight from the table, so it is right even when the publisher is the thing that is broken |
| `till_idempotency` keeps growing past its window | rows are only forgotten once the matching outbox event has gone. Check `till.retention.outbox` and whether the publisher is draining |
| A caller reports an error you cannot find | ask for the `requestId` in the body. It is in every log line for that request |

## What till does not do

Stated here rather than discovered: no multi-tenancy, no reservation of a quantity range or a
specific serial number, no partial fulfilment of a multi-line hold, no backorders, no pricing, no
scheduled availability, no read replicas, no rate limiting, and no encryption of data at rest beyond
whatever the disk does. [`SECURITY.md`](../SECURITY.md) is explicit about the security half of that
list and [`design/0005-scope.md`](design/0005-scope.md) about the rest.
