# Architecture

Two services, a storefront, and a ledger underneath that decides every sale. Eight modules, and the
dependency arrows all point the same way — the only structural rule the project has:

```
till-core      the rules, as a pure function.      no Spring, no I/O, no threads, no clock
   ^
   |-- till-jdbc      PostgreSQL: reads a snapshot, writes a decision, or neither
   |-- till-testkit   a deterministic simulator and the invariants it checks
   |-- till-kafka     the outbox to Kafka: plain kafka-clients, no Spring
   |-- till-client    an HTTP client and tillctl
        ^
        |-- till-server   the ledger service: REST, OpenAPI, metrics, the sweeper, the publisher
        |-- till-store    the store: catalogue, orders, sign-in, the operator API — a backend-for-frontend
                 ^
                 |-- till-web   the storefront and operator console, served by the edge
```

`till-core` has one runtime dependency, `slf4j-api`, and it is an API-only facade. Nothing below the
two services knows Spring exists, nothing but `till-web` knows a browser exists, and `till-store`
reaches the ledger only through `till-client`, over HTTP, with an ordinary client token.

What runs, and who may talk to whom:

```
browser ──> edge (nginx) ──> till-store ──> till-server ──> PostgreSQL (till)
   │                            │   │                          │
   │                            │   └──> PostgreSQL (store), Redis (sessions)
   │                            │                              │
   └──> identity provider <─────┘   Kafka <── the outbox ──────┘
        (Keycloak locally,          │
         Cognito in production)     └──> till-store's consumer ──> the store's projection
```

## The three-bucket model

Stock is two stored numbers and one derived one:

```
onHand      units the warehouse holds
reserved    units held by open reservations
available   onHand - reserved
```

A reservation raises `reserved`. Committing lowers **both** — the goods left and the hold on them
went with them. Releasing or expiring lowers only `reserved`. Nothing else moves either number
except an explicit adjustment.

That is the whole model, and it is worth being explicit about what it is not. It is not
`UPDATE stock SET quantity = quantity - ? WHERE sku = ? AND quantity >= ?`, which is how this is
usually written and which has no way to say "set this aside for fifteen minutes while the customer
finds their card". The three buckets exist so that the window between "I want this" and "I have paid
for this" is a state the system can hold, expire, and report on.

## The kernel is a function

```java
Decision decide(Snapshot snapshot, Command command, Instant now)
```

No fields. No connection. No thread. No clock — the instant arrives as an argument, exactly like the
rows do. Two calls with equal arguments return equal results, on any machine, in any order, forever.

That is not purity for its own sake. It is what makes the simulator in `till-testkit` possible: a
whole day of contention between eight callers, with crashes and clock jumps, becomes a function of
one integer seed, running at about eighty-five thousand steps a second, checking every invariant
after every step. Rules that live inside a transaction can only be tested at the speed of a database,
which is about four orders of magnitude slower and not reproducible.

The cost is that the kernel cannot fetch what it needs. Everything a command could touch has to be
loaded first, and getting that wrong has to be a loud failure rather than a quiet one — hence
`IncompleteSnapshotException`, which says "the adapter did not load this" rather than letting a
missing row look like a SKU that is out of stock.

## Decision is the seam

`decide` returns three things and a fourth that matters as much:

| | |
| --- | --- |
| `outcome` | what the caller is told |
| `mutations` | row changes, each carrying the version it expects |
| `events` | outbox rows |
| `outcomeRecord` | the idempotency record to insert |

A `Ledger` applies **all of it or none of it, in one transaction**. That is the contract the type
exists to make hard to break, because every way of breaking it is a real failure:

- Stock lowered without the reservation moving to `COMMITTED` is stock that has left the building and
  is still promised to somebody.
- An outbox row written outside the transaction can describe a change that was rolled back, and one
  written after it can be missing for a change that was not.
- An outcome recorded without the mutations beside it turns a retry into a confirmation of something
  that never happened.

A rejection can still carry mutations. Committing a hold that ran out of time is refused, and the
same decision writes that hold off, because the kernel has just established that it is expired and
throwing that away would mean discovering it again on the next command.

## The loop

```java
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    Instant now = clock.instant().truncatedTo(MICROS);
    Snapshot snapshot = ledger.load(command, now, reclaimLimit);
    Decision decision = Kernel.decide(snapshot, command, now);
    if (!decision.writes()) return decision.outcome();   // a replay, or an empty sweep
    if (ledger.apply(decision)) return decision.outcome();
}
throw new ConflictException(command, maxAttempts);
```

Four lines, and each of them is load-bearing.

**Optimistic, not locked.** The contended case in a flash sale is thousands of callers on one SKU,
and a row lock turns that into a queue whose length is everyone's latency. Nothing is held between
the read and the write, so a caller that thinks for a second blocks nobody.

**No backoff.** A conflict means the row moved, which means somebody else's transaction committed,
which means progress was made. Sleeping would only add latency to a system that is making progress.
Where backoff belongs is in the caller that catches `ConflictException`; `TillClient` has it, and it
retries with the same idempotency key, which is the only reason retrying is safe.

**Truncated to microseconds** because that is the resolution PostgreSQL stores. An instant that loses
precision on the way to disk comes back different, and a recorded outcome that no longer equals the
one that was returned is not a recorded outcome.

## The adapter

`till-jdbc` does two transactions per attempt, and the difference between them is the point.

Reading happens in a **read-only repeatable-read** transaction. A snapshot has to be one instant: at
read committed, the four statements it takes to assemble one would each see a different instant —
each row correct, the set of them describing a state that never existed. No version check catches
that, because every row individually is at the version it was read at.

Writing happens at **read committed**, with every statement carrying the version it expects. If the
row moved, the update matches no row, the transaction rolls back, and `apply` returns `false`.

`false` is not an error. It is the ordinary outcome of two callers reaching the same row, and the loop
answers it by loading again. A **check constraint violation** is not treated that way and raises
instead, because that means the application tried to write a level the database knows is impossible,
and retrying it would spin around a real bug forever.

Conflicts are detected with `on conflict do nothing` and a row count rather than by catching a unique
violation. A failed statement inside a PostgreSQL transaction aborts the whole transaction, so the
exception route makes every conflict cost a rollback of work already done — and makes the code read
as though an exception were the expected case.

## Where the idempotency actually happens

The interesting part is not the table. It is the unique constraint on it.

Two copies of one request arrive at two servers at the same instant. Both load a snapshot with no
record, both decide to act, both try to insert. One transaction wins. The other is refused by the
constraint, returns `false`, reloads, finds the record, and replays it. Neither the database nor the
kernel needs a lock for that to be true, and there is no window in which both succeed.

Two details are easy to get wrong and are both tested:

- A `Reserve` fingerprint **excludes the reservation id**. A stateless server mints an id per HTTP
  request, so including it would make every retry look like a different request and be rejected as a
  key reuse.
- Lines are canonicalised — sorted by SKU, duplicates refused — before the fingerprint is taken, so a
  body that lists the same SKUs in a different order is the same request.

## Deadlines, and why the sweeper is optional

A hold past its deadline is expired **whether or not anything has written that down**. Reading
`state = 'HELD'` straight out of the row is the bug this rule exists to prevent: it would make the
answer to "may this commit?" depend on whether a background job happened to have run, which is not a
property a caller can reason about and not one a test can reproduce.

So `Reservation.effectiveState(now)` is the truth and the stored state is a cache of it. Every command
writes off the expired holds standing in its way as it passes, which means:

- The sweeper returns stock to `available` sooner. It never makes a wrong answer right.
- Turning the sweeper off makes stock come back later, never never.
- A SKU under load reclaims itself, because the next reservation to need those units does it.

The reclaim is scoped to the SKUs the command is about. Writing off an unrelated hold would touch a
row the command has no reason to touch and turn an unrelated caller's commit into a conflict.

## Threads

`Till` and `JdbcLedger` are immutable and safe to share. `Kernel` has no state at all. The server
runs the sweeper and the publisher on Spring's scheduler, and both are safe on every instance at
once: two sweepers reaching the same hold produce one write and one conflict, and the conflict is
answered by doing nothing, because the hold is now in the state the loser wanted.

The publisher is at-least-once by construction — it marks rows **after** delivering them, so a crash
in between repeats the send. Two publishers can deliver the same batch. That is the consumer's to
drop, which it can, because every event carries a deduplication key that is a function of what
happened rather than of when it was published.

## The store

`till-store` owns everything a customer sees that is not a stock level: the catalogue, search,
orders, sign-in, and the operator console's API. It is a backend-for-frontend — the one server the
storefront talks to — and three decisions shape it.

**It cannot move stock.** It reserves, commits and releases through the ledger's public API with a
client token, exactly as any other client would, on a database of its own. The admin token it also
holds is used only behind `/api/ops`, which answers only members of the identity provider's admin
group. So the worst a bug in the store can do to stock is fail to ask for it.

**It believes the ledger, eventually.** Availability on a store page comes from a projection of the
ledger's events, consumed from Kafka through an inbox that applies each event once. It lags, and it
says so — every level carries the ledger's decision instant — and a stale number costs one customer
a refused checkout, never an oversell, because it is not consulted when a sale is decided. Orders
converge the same way: the HTTP answer moves an order to paid, and if that answer is lost, the
commit event does it. Every transition is guarded by the state it expects, so a late event can never
walk an order backwards.

**The browser holds no token.** Signing in is the OpenID Connect authorization-code flow with PKCE,
run on the server; the tokens stay in the session, in Redis; the browser gets an `HttpOnly`,
`SameSite=Lax` cookie and nothing it could leak. Every write also needs the `X-XSRF-TOKEN` header,
copied from a cookie only a script on the store's own origin can read. Locally the provider is
Keycloak and in production it is Amazon Cognito; the same code runs against both, because Keycloak
is configured to name its groups claim the way Cognito does.

A customer's idempotency key is never passed to the ledger as sent. The ledger's key space is global
and a browser's is not, so two customers could send the same string — and the ledger would, correctly
by its own rules, hand the second customer the first one's hold. The store sends a digest of the
purpose, the customer and the key instead (`LedgerKeys`): a retry is still a retry, and two customers
can no longer collide. That was a real bug in the first storefront, invisible because every demo had
one user.

## The storefront

`till-web` is a React and TypeScript single-page application: browse, search and filter the
catalogue, a page per game, a cart, a two-step checkout with a visible hold, order history, and — for
the admin group — the operator console. It holds no token of any kind, and every request it makes
goes to its own origin.

**State is Redux Toolkit, and server state is RTK Query.** Four unrelated parts of the page read the
cart, and one of them — checkout — has to rewrite it when the store reports a shortfall, so the cart
is a slice. Everything fetched is an RTK Query endpoint with cache tags, so paying for an order makes
the order list stale in one declaration rather than in every component that happens to pay.

**The two headers that are its whole security story are set in one place.** `storeApi.ts` copies the
CSRF token into every write and passes the caller's idempotency key. Which key counts as "the same
attempt" is the caller's decision, and checkout makes it carefully: the key for placing an order is
the attempt plus a fingerprint of the cart, so a retry of the same cart replays the order already
placed, a changed cart is a new request, and a paid order starts a new attempt.

**The types are generated.** `openapi/store.json` is the store's contract; `OpenApiContractTest`
regenerates it from the running service and fails when the committed copy is stale, and the SPA's
types are generated from it. A renamed field is a compile error, not an `undefined` on a page.

**The cover art is painted, not shipped.** Each game names a motif, and its SKU seeds the details, so
thirty-two games get thirty-two distinct covers from twelve small SVG scenes, with no image the
project does not own.

## The edge

nginx is the only thing a browser talks to. It serves the SPA's files and proxies the API, the
sign-in round trip and the API documentation to the store; the ledger is not reachable through it at
all.

- **One origin**, so the session cookie is first-party and the API needs no CORS.
- **Security headers from one place**, including a Content-Security-Policy that forbids inline
  script — which is why the pre-paint theme script is a file.
- **Rate limits** per address, generous for reads and strict for writes, answered with a `429`
  problem in the store's own shape rather than an HTML page.
- **A five-second microcache** for the catalogue. The store marks those responses `public` with a
  bounded `stale-while-revalidate`, and sets no cookie on them — a shared response must never carry
  one. How stale the cache may serve is the origin's decision: an earlier configuration let nginx
  serve stale "while updating", with no upper bound, and one refetch it could not store froze an
  entry for as long as traffic kept it warm.

## Further reading

| | |
| --- | --- |
| [`testing.md`](testing.md) | what each layer proves, and what the suite does not cover |
| [`operations.md`](operations.md) | running it: tuning, monitoring, retention, backup, upgrades |
| [`design/`](design/) | one note per decision, each with its costs and rejected alternatives |
