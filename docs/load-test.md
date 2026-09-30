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
