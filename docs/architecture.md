# Architecture

Six modules. The dependency arrows all point the same way, and that is the only structural rule the
project has:

```
till-core      the rules, as a pure function.      no Spring, no I/O, no threads, no clock
   ^
   |-- till-jdbc      PostgreSQL: reads a snapshot, writes a decision, or neither
   |-- till-testkit   a deterministic simulator and the invariants it checks
   |-- till-client    an HTTP client and tillctl
        ^
        |-- till-server   REST, OpenAPI, metrics, the sweeper, the outbox publisher
                 ^
                 |-- till-web   the browser console, bundled into the jar by `-Pweb`
```

`till-core` has one runtime dependency, `slf4j-api`, and it is an API-only facade. Nothing below
`till-server` knows Spring exists, and nothing below `till-web` knows a browser exists.

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

## The console

`till-web` is a React and TypeScript application with two routes, served by the service itself from
`/static`. `/shop` is a shop front — a basket, a hold with a visible countdown, a Pay button — and it
exists to show why reservations are worth having: open it in two tabs, race for the last unit, and
exactly one tab gets it. `/ops` is the operator view: the ledger's totals, the stock split per SKU,
the reservations with their states, and the outbox backlog.

Three things about it are load-bearing.

**The catalogue is in the frontend.** Names, prices and pictures are the shop's business; how many
there are is the ledger's. till never joins to a product table, and this is the demonstration of that
rather than a gap in it.

**The types are generated from a committed `openapi.json`.** `OpenApiContractTest` regenerates that
file from the running service and fails when the committed copy has gone stale, so a change to an
endpoint that nobody regenerated is a red build rather than a console compiled against an API that no
longer exists. CI additionally re-runs the generator and fails on a diff.

**The route forward is enumerated.** A reload of `/ops/reservations` arrives as a request for a path
no controller has. The usual answer is a catch-all forward to `index.html`; the usual bug that comes
with it is that the catch-all also swallows `/v1/nonsense` and returns HTML, which a client parses as
JSON and reports as a corrupt response. Only the console's own routes are forwarded, and there is a
test for the difference.

The console is built by an opt-in Maven profile. `mvn package` produces a jar with an API and no
console — and says so at startup; `mvn -Pweb package` produces one with both. CI and the release
build with the profile. [`design/0007-browser-console.md`](design/0007-browser-console.md) has the
reasoning, including what a product would do about authentication that this deliberately does not.

## Further reading

| | |
| --- | --- |
| [`testing.md`](testing.md) | what each layer proves, and what the suite does not cover |
| [`operations.md`](operations.md) | running it: tuning, monitoring, retention, backup, upgrades |
| [`design/`](design/) | one note per decision, each with its costs and rejected alternatives |
