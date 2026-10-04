# The load test

What the platform's performance figures mean, how they are measured, and what counts as meeting
them. This was written before the first run, and it does not change to fit a result: a run that
misses a target is reported with the numbers it got.

## Targets

| | Measured as | Target |
| --- | --- | --- |
| **Concurrent users** | virtual users active at once through the steady window, each a separate customer: its own cookies, CSRF token, address, and — once it checks out — signed-in identity | **8,000** or more |
| **Transactions per second** | HTTP requests through the edge completed per second over the steady window, in the mix below: page loads, the catalogue, search, a game's page, signing in, orders, payments | **3,000** or more |
| **Latency** | the 99th percentile of request duration over the steady window, every request through the edge | **under 1 s** |
| **Errors** | responses other than the one the request expects, or an expected refusal | **under 0.1%** |

Reported beside them, without a target: orders placed, paid and cancelled per second; the 99th
percentile for each class of request; and what the load balancer, the database and the containers
said about the same window.

"Transaction" is a request, as in the targets above, because the targets are about the platform
serving a crowd of shoppers. What a shopper does is mostly reading, and checking out is the part
worth watching closely, so it has figures of its own rather than being hidden in the total.

## The shopper

Every virtual user is one customer and does what the storefront does in a browser:

1. **Arrives**: the page (`/`), who am I (`/api/me`), the front page (`/api/home`).
2. **Shops**, over and over, with 1–4 s of thinking between actions:
   - a genre's page of games, or a search (60 / 40), with terms drawn from the catalogue's own titles
     and tags so most of them find something;
   - a game's page.
3. One time in ten, **checks out**: signs in the first time (the whole OpenID Connect round trip),
   places an order for one or two games, and pays for it — or, one time in ten, cancels it instead.

That is a request every two and a half seconds or so per shopper, so 8,000 of them should make
about 3,500 requests a second — about 150 of them orders, and as many again payments and
cancellations. That is the design; the run measures what they actually make. Nobody gets
rate-limited for shopping like this: the edge allows each address 30 reads and 5 writes a second.

## What is in the path

**The load enters at the load balancer**, from a load generator in the same VPC. CloudFront is not
in the path: its capacity is AWS's rather than this platform's, and through it every virtual user
would arrive from the load generator's one address. Instead each virtual user sends its own
`CloudFront-Viewer-Address`, from 198.18.0.0/15 — the range set aside for benchmarking — and the
edge believes it exactly as it believes CloudFront's. So the rate limits are in the path, per
customer, the way they are in production.

**Sign-in goes to a stand-in OpenID provider** ([`till-loadtest`](../till-loadtest)), running in the
VPC, which approves every request without a password. The store does its whole side of the flow:
PKCE, the nonce, the ID token's signature, issuer and audience, the session in Redis. Cognito is not
in the path — its sign-in pages are not what is being measured, and its request quotas would be.

**Everything else is the deployment as it runs**: the edge with its rate limits and catalogue cache,
the store, the ledger, Kafka and the projection, PostgreSQL, the session cache. Two settings differ,
and both are because the load generator speaks plain HTTP inside the VPC:

- the session cookie is not marked `Secure`, or the load generator's HTTP client would not send it
  back;
- the edge's access log is off. At 3,000 lines a second it is 3 GB an hour, $1.60 an hour at
  CloudWatch's price; the load balancer's metrics count the requests instead.

Static assets are not fetched. In production CloudFront serves them from its cache for a year, so
nothing a load test could measure would be serving them.

**Every game's stock is split sixteen ways** ([ADR 9](design/0009-hot-sku-shards.md)) as the store
stocks it, the way an operator would split a game about to be busy: every game is busy here, with
8,000 shoppers over thirty-two of them. `scripts/aws.sh up --loadtest --shards=1` measures with one
row each, as the first runs had.

## What the catalogue cache is worth

A figure of its own, measured the same way every time:

- **Two runs of the protocol against one deployment**, the store's catalogue cache off
  (`scripts/aws.sh up --loadtest --no-catalogue-cache`) and then on (`scripts/aws.sh up --loadtest`,
  which changes only the store), with everything else — shoppers, sizes, database — the same.
- **Average query latency** is the store's own account of its catalogue reads over the steady window:
  the time from asking for a catalogue answer to having it, from Valkey or from the database, summed
  and divided by the number of reads. Each store logs it once a minute (`catalogue-reads`), and
  `scripts/aws.sh loadtest` adds it up for the window and saves it with the result.
- Both runs report the targets above as well, since the cache is also a change to everything that
  was waiting for the database behind the catalogue.

## Against Aurora

`scripts/aws.sh up --loadtest --database=aurora` runs the same protocol against Aurora PostgreSQL
Serverless v2 instead of RDS — nothing else in [the sizes](../infra/loadtest.tfvars) or the protocol
changes. The free plan caps it at 4 ACU and 1 GiB of storage per cluster
([infra/README.md](../infra/README.md) has what that costs).

Record the same figures as any other run, plus `database` and, from the saved result's
`cloudwatch.db_acu_max_capacity` (`ServerlessDatabaseCapacity`), whether the run pinned the 4 ACU
ceiling for its whole window — the Aurora equivalent of the 98% CPU the `db.t4g.micro` runs below
were at throughout. No run against Aurora has been recorded yet.

## With the store on a server of its own

`scripts/aws.sh up --loadtest --database-per-service` runs the same protocol with the store on a
PostgreSQL server of its own instead of sharing the ledger's — nothing else in
[the sizes](../infra/loadtest.tfvars) or the protocol changes, and it composes with
`--database=aurora` the same way `--shards` and `--no-catalogue-cache` do.
[infra/README.md](../infra/README.md) has what a second server costs.

Record the same figures as any other run, plus `database_per_service` and, from the saved result's
`cloudwatch`, `store_db_cpu_max_percent` (and, against Aurora, `store_db_acu_max_capacity`) beside
the ledger's own `db_cpu_max_percent` — the point of a second server is whether either one stops
being the 98% CPU the one shared `db.t4g.micro` ran at for the whole window below, now that the
store's statement time no longer shares the ledger's cores or its connection budget.
`database_top_statements` carries both servers' statements either way, each row already naming its
`db`, and `database_transactions` is still keyed by database name regardless of which server it came
from. The first run like this is [below](#1-and-2-october-work-for-nobody-and-then-the-store-on-a-server-of-its-own).

## Pool sizes

`scripts/aws.sh up --loadtest --db-pool=STORE,LEDGER` replaces
[`loadtest.tfvars`](../infra/loadtest.tfvars)' `db_pool` — eight connections for each store and
sixteen for each ledger — for that deployment, and the result records the sizes a run used as
`db_pool`. Changing only this replaces the services and keeps the servers, so one deployment can be
measured at more than one size. It is there because the ledger's server has two vCPUs and each
ledger held sixteen connections with about 180 requests queued behind them
([below](#1-and-2-october-work-for-nobody-and-then-the-store-on-a-server-of-its-own)): whether
fewer connections would get more done is a thing to measure, not to assume.

## A run

1. `scripts/aws.sh up --loadtest` deploys with the sizes in
   [`infra/loadtest.tfvars`](../infra/loadtest.tfvars) — two of each service, and the largest
   database the account's AWS plan allows, a `db.t4g.micro` — and adds the stand-in provider and
   the load generator.
2. `scripts/aws.sh loadtest --shoppers=100 --ramp=20s --hold=1m` first: a minute that proves the
   path works before the run that costs a quarter of an hour.
3. `scripts/aws.sh loadtest`: from none to 8,000 virtual users over five minutes, held for ten —
   the steady window — and down over thirty seconds. Only the steady window counts.
4. It prints the load generator's summary, then what the load balancer, the database and the
   containers told CloudWatch about the same window, and saves both, with the commit, to
   [`till-loadtest/results/`](../till-loadtest/results).
5. Record the result below, whether or not it met the targets, and `scripts/aws.sh down`.

The load generator is one k6 task of 8 vCPUs and 16 GB. On a laptop k6 used about 0.44 MB per
virtual user, so 8,000 of them need about 3.5 GB; the rest is headroom, and the CPU is for the
requests.

## Results

| Run | Commit | Set up as | Shoppers | Requests/s | p99 | Errors | Orders/s | Targets |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| [30 Sep 2026](../till-loadtest/results/20260930T102728Z.json) | `e378bd9` | edge 2 × 0.5 vCPU, store and ledger 2 × 1 vCPU, db.t4g.micro | 8,000 | 2,855 | 20.8 s | 41.5% | 3.8 | **missed**: throughput, latency, errors |
| [30 Sep 2026, second](../till-loadtest/results/20260930T114312Z.json) | `e01bbeb` | as above, the edge with its own nginx.conf and 2 × 1 vCPU | 8,000 | 2,497 | 21.1 s | 4.6% | 3.2 | **missed**: throughput, latency, errors |
| [30 Sep 2026, cache off](../till-loadtest/results/20260930T125908Z.json) | `b314cae` | as above, four stores at 8 connections each; the catalogue cache off | 8,000 | 2,518 | 20.9 s | 4.5% | 0 | **missed**: throughput, latency, errors |
| [30 Sep 2026, cache on](../till-loadtest/results/20260930T133217Z.json) | `a699e06` | the same, the catalogue cache on | 8,000 | 2,500 | 20.8 s | 4.5% | 0 | **missed**: throughput, latency, errors |
| [30 Sep 2026, lazy reclaim](../till-loadtest/results/20260930T221848Z.json) | `af0ce39` | the same; expired holds written off only when a command is short of stock | 8,000 | 2,513 | 20.8 s | 4.5% | 7.9 | **missed**: throughput, latency, errors |
| [1 Oct 2026, one row a game](../till-loadtest/results/20261001T024424Z.json) | `03e5385` | the same; a deadline on the store's calls to the ledger, stock rows written first, snapshots without session statements, the event stream draining to twelve partitions; every game's stock in one row | 8,000 | 3,177 | 5.0 s | 4.5% | 8.9 | **met**: throughput; **missed**: latency, errors |
| [1 Oct 2026, sixteen rows a game](../till-loadtest/results/20261001T045708Z.json) | `03e5385` | the same, every game's stock split sixteen ways | 8,000 | 3,176 | 5.1 s | 4.5% | 10.1 | **met**: throughput; **missed**: latency, errors |
| [1 Oct 2026, work for nobody](../till-loadtest/results/20261001T105202Z.json) | `6966502` | the same; the ledger refusing work its caller stopped waiting for, a lean snapshot in one statement, three Kafka brokers | 8,000 | 3,175 | 5.2 s | 4.5% | 11.7 | **met**: throughput; **missed**: latency, errors |
| [2 Oct 2026, the store on its own server](../till-loadtest/results/20261002T063658Z.json) | `6966502` | the same, the store's database on a db.t4g.micro of its own | 8,000 | 3,312 | 5.0 s | 1.6% | 103.3 | **met**: throughput; **missed**: latency, errors |
| [2 Oct 2026, a login of its own](../till-loadtest/results/20261002T095810Z.json) | `a795eac` | the same; a new connection's login bounded on its own, a ledger out of connections saying OVERLOADED in one line | 8,000 | 3,298 | 5.0 s | 1.7% | 100.9 | **met**: throughput; **missed**: latency, errors |
| [2 Oct 2026, eight connections a ledger](../till-loadtest/results/20261002T103317Z.json) | `a795eac` | the same, each ledger holding eight connections instead of sixteen | 8,000 | 3,278 | 5.0 s | 2.1% | 91.1 | **met**: throughput; **missed**: latency, errors |
| [3 Oct 2026, with Database Insights](../till-loadtest/results/20261003T004854Z.json) | `6069d4b` | sixteen connections again; Database Insights recording the load | 8,000 | 3,261 | 5.0 s | 2.2% | 87.9 | **met**: throughput; **missed**: latency, errors |
| [4 Oct 2026, one call each way](../till-loadtest/results/20261004T002219Z.json) | `d412174` | the same; a decision written in one call to `till_apply`, a snapshot statement that neither sorts nor joins | 8,000 | 3,298 | 5.0 s | 1.5% | 106.7 | **met**: throughput; **missed**: latency, errors |
| [4 Oct 2026, holds without waiting for the disk](../till-loadtest/results/20261004T053709Z.json) | `f5c32f3` | the same; a hold commits without waiting for its WAL to be flushed | 8,000 | 3,315 | 5.0 s | 1.5% | 108.0 | **met**: throughput; **missed**: latency, errors |

### 30 September: the first run

It held 8,000 shoppers and missed every other target, and the load balancer's own count (2,858
requests a second) agrees with the load generator's. Two things gave way.

**The edge ran out of connections.** nginx allows 1,024 per worker process by default, and said
`worker_connections are not enough` thousands of times; every connection it dropped, the load
balancer answered itself — 599,384 of its own 5xx in nine minutes. Its CPU was at 99% too: half a
vCPU each, compressing every response.

**The database was at 98% CPU for the whole window**, with all 64 pooled connections busy and 90 MB
of its gigabyte free. The ledger's queries timed out, the store answered checkouts with 503s, and
orders fell to 3.8 a second against the 150 the scenario asks for. What the database was spending
that CPU on is not known yet — orders were few — and is the next thing to find out.

The p50 of 1.6 ms is the edge's catalogue cache answering most requests at once; the p99 of 20.8 s
is requests waiting for a database connection that did not come free.

### 30 September: the second run

The edge's own `nginx.conf` — 16,384 connections a worker, keep-alive longer than the load
balancer's — and a whole vCPU each: the load balancer's own 5xx went from 599,384 to none, and the
edges ran at 31% CPU. Unexpected responses fell from 41.5% to 4.6%, every one of them a checkout.

Throughput fell too, to 2,497 requests a second, because requests that the first run's load
balancer had refused at once now waited — for the database, still at 98% CPU, and for the store,
now at 93%.

This run also asked the database what it spent its time on (`pg_stat_statements`, saved with the
result). **The catalogue**: its searches at 26 and 39 ms each and its tag counts at 11 ms came to
about 1,045 seconds of execution in a fifteen-minute run — more than half of what two cores can do.
The ledger's writes took more in total, but at 5 to 9 ms a statement for updates and inserts by key,
that is time spent waiting for the CPU and for row locks rather than working.

And the p99 for placing an order was 30,002 ms: the store's connection pool's thirty-second wait.
The store's sixteen connections were held by catalogue queries a tenth of a second long, and a
checkout queued behind them until it gave up. A catalogue that asks the database once a minute
instead of for every miss is the next change.

### 30 September: the catalogue cache, off and on

The comparison [above](#what-the-catalogue-cache-is-worth), run as written: one deployment, the
store's catalogue cache off and then on, nothing else changed — the commit between the two runs
added the first run's result and no code.

| | Cache off | Cache on |
| --- | ---: | ---: |
| Catalogue reads over the steady window | 24,868 | 25,969 |
| from Valkey | — | 24,726, at 0.70 ms |
| from the database | 24,868, at 397.67 ms | 1,243, at 705.24 ms |
| **Average query latency** | **397.67 ms** | **34.42 ms**, 91% less |
| Catalogue p99 through the edge | 288.9 ms | 85.3 ms |
| Search p99 through the edge | 283.9 ms | 85.5 ms |

The cache did what it was for: nineteen in twenty catalogue reads never reached the database, and
the average read took a tenth of the time. The run's targets did not move — both runs missed all
three — because the database did not get quieter: it was at 98% CPU in both, and with the catalogue
off it, the ledger took the lot. Its most expensive statements were marking reservations expired
(319,118 of them, 42 ms each) and looking for expired holds to reclaim (55,871 times, 110 ms each),
and no order completed in either steady window.

That is the next thing: the store gives up on a reservation after five seconds, the ledger does not,
and every checkout the store abandoned became a hold that nobody would pay for and the ledger would
have to find and expire. The more of them there were, the longer each search for them took, and the
fewer checkouts finished inside five seconds.

### 30 September: expired holds written off when they are needed

The change the previous run asked for: a command writes off expired holds only when the answer
without them would be "not enough stock", and the sweeper writes off the rest, in batches, between
commands. The storm went. The ledger changed the state of 43,393 reservations, at 3.1 ms each, where
the run before changed 319,118 at 42 ms; the search for expired holds is no longer among the
database's busiest statements; and orders completed under the full load for the first time, 7.9 a
second.

The targets did not move, and the database was at 97% CPU again. What it spent that on now is the
checkout itself, and most of it was work thrown away:

| Over the whole run | |
| --- | ---: |
| Stock updates | 278,525 |
| — that found the row had moved since it was read | 148,896, 53% |
| Reservations inserted | 197,426 |
| — rolled back when the stock update after them failed | 125,681, 64% |
| Transactions committed in the ledger's database | 1,484,087 |
| Holds still held when the run ended | 50,394 of 71,745 |

The statement counts are the result's. What survived them — the reservations that remained, the
commits, the holds — was read from the database after the run and before it was taken down; the
result does not record those yet.

Three things compound:

- **A conflict is found last.** The ledger inserts the reservation, then its lines, then updates the
  stock row with the version it read. Under optimistic concurrency the stock update is the statement
  that fails, and it came after two inserts that its failure rolled back.
- **Every failure is retried twice over.** The ledger decides a command up to eight times before it
  answers 503, and the store's client makes each call up to four times with a five-second timeout.
  The p99 of every run so far, 20.8 s, is those four timeouts and the waits between them: a checkout
  the ledger could not answer in time kept its customer waiting twenty seconds to be told to try
  again, and kept the ledger busy all the while.
- **A snapshot costs four statements that read nothing.** Each load asked the connection for its
  isolation level, set it to repeatable read, began read-only, and set it back afterwards — 345,639
  loads, four statements each, three of them a transaction of their own. They are most of the 1.48
  million commits.

The holds are the cost of the second. The ledger took 71,745 over the run's sixteen minutes, and
the steady window's ten placed 4,740 orders: even pro rata, ten holds for every order a shopper saw.
The difference is checkouts the store stopped waiting for while the ledger was still working on them. The ledger took the hold, and nobody was left to hear
about it. No shopper was refused for want of stock, so they cost nothing until they expire; but each
one is a customer who was told the checkout had failed.

The next change takes the three in turn: the store's calls to the ledger get a deadline of five
seconds in all, retries included; the ledger writes the rows it checks versions on first, so that a
conflict costs one update rather than two inserts and a rollback; and a snapshot is read in a
transaction that sets its own isolation, with nothing to set back. The conflicts themselves are what
hot-SKU inventory sharding is for, which comes after and will be measured against this.

### 1 October: the checkout's waste, and then hot-SKU shards

Two runs against one deployment of `03e5385`: first with every game's stock in one row, then with
every game split sixteen ways ([ADR 9](design/0009-hot-sku-shards.md)) — the store splits them as it
starts, so between the runs only the store's `STORE_DEMO_SHARDS` changed. The second deployment of
the store had to be made one task at a time: ECS's default rollout started four new stores beside the
four old ones, and the database ran out of connections (it refuses new ones at about seventy; the two
services hold sixty-four). The deployment does that by itself now.

| | Run 4 | One row a game | Sixteen rows a game |
| --- | ---: | ---: | ---: |
| Requests a second | 2,513 | **3,177** | **3,176** |
| p95 / p99 | 1,163 ms / 20.8 s | 1,077 ms / 5.0 s | 867 ms / 5.1 s |
| p99 of orders | 21.8 s | 6.2 s | 6.4 s |
| Unexpected responses | 4.47% | 4.47% | 4.49% |
| Orders placed, paid, a second | 7.9, 1.6 | 8.9, 1.6 | 10.1, 1.3 |
| Stock updates that found the row had moved | 53.5% | 66.4% | **16.5%** |
| Ledger transactions committed, rolled back | 1,484,087, 149,034 | 412,898, 220,863 | 157,393, **14,748** |
| Ledger CPU, database CPU (maximum) | 79%, 97% | 47%, 98% | 23%, 98% |

What moved:

- **The deadline did what it was for.** A checkout the ledger could not answer in time used to wait
  out four five-second attempts; it now waits five seconds in all. The p99 went from 20.8 s to 5.0 s,
  and shoppers who are told sooner move on sooner: the same 8,000 made 3,177 requests a second, and
  the throughput target was met for the first time.
- **The snapshot change** shows in the ledger's transactions: 413 thousand where run 4 had 1.48 million.
- **With one row a game, two stock updates in three found their row had moved** — more than run 4,
  because the deadline let more checkouts in to fight over the same thirty-two rows. **Sixteen rows
  took that to one in six**, the rollbacks from 220,863 to 14,748, the ledger's CPU from 47% to 23%,
  and the p95 from 1,077 ms to 867 ms. The contention benchmark predicted the direction; here it is at
  full scale.

What did not:

- **Latency and errors.** The database was at 98% CPU in both runs. With the conflicts gone the work
  that is left is ordinary, and every piece of it waits for two CPUs: a reservation's line took 19 ms
  to insert, setting a snapshot's isolation 9 ms, the store's order 38 ms. The store gives up on the
  ledger after five seconds, the ledger is waiting on the database, and the 4.5% that fail — about 143
  responses a second — are those checkouts: 10.1 orders a second were placed and 1.3 paid, so most
  attempts to place or pay did not get through.
- **The holds.** 41,259 of the second run's 48,744 holds were still held when it ended: checkouts the
  store stopped waiting for while the ledger, behind the database, was still working on them.

So the constraint is now the database itself: a `db.t4g.micro` — two burstable cores and a gigabyte,
the largest the account's plan allows — serving the store's catalogue misses, availability, orders
and projection, and all of the ledger, through sixty-four connections. Next: the store and the ledger
on databases of their own, fewer connections queueing for the cores, and Aurora Serverless measured
the same way.

### 1 and 2 October: work for nobody, and then the store on a server of its own

Two deployments of `6966502`. The first ran with everything the 1 October runs had, sixteen rows a
game included, and three changes since: the ledger refuses a command its caller has stopped waiting
for ([ADR 11](design/0011-request-deadlines.md)), reads a command's snapshot in one statement
([ADR 12](design/0012-one-statement-snapshot.md)), and Kafka runs three brokers
([ADR 13](design/0013-kafka-replication.md)). The second was the same with the store's database on a
`db.t4g.micro` of its own (`--database-per-service`). It ran the next morning, on a deployment of its
own: the first attempt lost its connection to AWS four minutes in.

| | Run 6 | One server | The store on its own |
| --- | ---: | ---: | ---: |
| Requests a second | 3,176 | 3,175 | **3,312** |
| p95 / p99 | 867 ms / 5.1 s | 935 ms / 5.2 s | **208 ms** / 5.0 s |
| p99 of orders | 6.4 s | 6.6 s | 5.0 s |
| Unexpected responses | 4.49% | 4.45% | **1.63%** |
| Orders placed, paid, a second | 10.1, 1.3 | 11.7, 2.3 | **103.3, 82.0** |
| Stock updates that found the row had moved | 16.5% | 11.7% | 3.2% |
| Holds still held when the run ended | 41,259 of 48,744 | 53,951 of 77,077 | **11,429 of 91,225** |
| Time the database spent on statements | 8,858 s | 7,035 s | 3,981 s |
| Database CPU (maximum) | 97.9% | 98.1% | the ledger's 86.2%, the store's 41.0% |

On one server, the database did more for less: 58% more holds taken, every common statement about
twice as fast as in run 6 (a stock update 14.7 ms to 6.8, a reservation's line 18.9 to 9.4), and a
snapshot in one statement at 10.1 ms where the three it replaced had taken about 20. But the server
was at 98% again, and nothing a shopper sees moved.

With the store on its own server, it did. The p95 fell from 935 to 208 ms and the errors from 4.45%
to 1.63%; nine times as many orders were placed and thirty-six times as many paid; and most holds now
became orders: 11,429 of 91,225 were still held when the run ended, where on one server 53,951 of
77,077 had been.

What did not move is the p99, and it is one kind of request. Every class but orders had its p99 well
under a second — pages 1.2 ms, the catalogue 30 ms, search 31 ms, sessions 114 ms, sign-ins 106 ms —
and orders 5.0 s: the store's five-second deadline on its calls to the ledger.

The ledger's own logs say where the five seconds go. Each ledger had about 180 requests waiting for
its sixteen connections throughout both runs, and its pool refused 35,000 commands on one server and
51,000 with the store on its own after two seconds without a connection — more in the second because
nine times as many checkouts got as far as the ledger. At the peak the pools lost connections, down
to ten or eleven of sixteen, and could not replace them: new connections timed out while negotiating
SSL (`SSL error: Read timed out`) against a server that busy. (A first reading of these logs put
that down to Hikari passing its two-second `connection-timeout` to the driver as a login timeout. It
does not, for a pool configured by URL: pgjdbc's own `loginTimeout` defaults to no limit, and the
read that timed out was one of the driver's own connect-phase limits.) The
deadline check refused 1,307 and 1,548 commands; the rest of the waiting is for a connection, which
the deadline does not bound, as ADR 11 records. Every refusal also logged a full stack trace, one
record a line: 2.8 and 3.6 million log records in the two windows.

So the next work is the ledger's pool: connections that can still be opened under load, the ledger
turning away at once what it cannot serve rather than after two seconds of queueing, and one line for
each refusal. Then Aurora, measured the same way.

### 2 to 4 October: where the ledger's database spends its time

Five runs, all with the store on a server of its own and sixteen rows a game; each has its trial
beside it in the results.

| | A login of its own | Eight connections | With Insights | One call each way | Holds without the disk |
| --- | ---: | ---: | ---: | ---: | ---: |
| Requests a second | 3,298 | 3,278 | 3,261 | 3,298 | 3,315 |
| p95 / p99 | 278 ms / 5.0 s | 285 ms / 5.0 s | 626 ms / 5.0 s | 596 ms / 5.0 s | **341 ms** / 5.0 s |
| Unexpected responses | 1.72% | 2.14% | 2.22% | 1.54% | 1.52% |
| Orders placed, paid, a second | 100.9, 80.1 | 91.1, 67.1 | 87.9, 64.6 | 106.7, 85.2 | **108.0, 86.0** |
| The ledger's transactions rolled back | 7.3% | 5.7% | 6.3% | 6.7% | **2.1%** |
| The ledger's database CPU (maximum) | 95.8% | 65.7% | 95.5% | 93.4% | 88.7% |

What did not help:

- **A login of its own.** The pool's new connections now have ten seconds to log in where they had,
  as it turned out, no limit at all. The run is the store-on-its-own run again, within the noise.
- **Fewer connections.** At eight a ledger the database's CPU fell from 96% to 66%, and fewer orders
  got through: the pool, not the server, became what checkouts waited for. At four, even the trial
  failed. The outbox's publisher and the sweeper take their connections from the same pool.

Then Database Insights, which samples the load every second, said where the time went. In the run
that recorded it, the ledger's server had 17.5 sessions active on its two vCPUs: 67% of them on the
CPU or waiting for it, 16% waiting for the application's next statement in the middle of a
transaction, and about 11% waiting on the WAL. Contention for the same pages was 2.3%. By statement,
the snapshot was 31% of the load, COMMIT 20%, the stock update 14%, BEGIN 5%, and the four inserts
about 21% together. Three changes followed from that: a decision written in one call to a function,
`till_apply` ([ADR 14](design/0014-one-round-trip-apply.md)), in place of a transaction of round
trips; a snapshot statement without the sorts, windows and joins that had been most of its cost
([ADR 12](design/0012-one-statement-snapshot.md)); and holds committed without waiting for the disk,
sales and adjustments still waiting ([ADR 15](design/0015-async-commit-for-holds.md)). Locally, the
first two were worth 40% and then a further 29% more checkouts a second.

Here they took the sessions on the ledger's server from 17.5 to 13.0 and then 11.1. Async commit
halved the time spent waiting on the WAL and cut rollbacks from 6.7% to 2.1%, because transactions
that finish sooner collide less. The p95 fell to 341 ms, and the orders paid a second went from 65 to
86. What is left is CPU: 67% of the load, `till_apply` 73% of it and the snapshot 18%.

The p99 did not move, and the reason is in the shape of the test rather than in any one run. It is a
closed loop: each shopper orders as soon as the last response arrives, so a faster checkout lets the
same 8,000 order more, until the ledger is full again. The trial's 100 shoppers placed 1.7 orders a
second; at 8,000 that would be about 136, and the ledger served 108. Until it can take every one of
them with room to spare, the orders that do not fit wait out the store's five-second deadline, and
those orders are the p99. Every other kind of request had its p99 under 175 ms.

Next is the ledger doing fewer, larger transactions: the commands queued for it decided together and
written in one call.
