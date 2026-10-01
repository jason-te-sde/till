# 11. The caller's deadline travels with the request

**Status:** accepted

## Context

The store gives every call to the ledger a deadline: five seconds in all, retries included
(`TillClient.Builder.deadline`, described in [`architecture.md`](../architecture.md), "The loop").
After that it tells the customer to try again. The ledger has no idea the caller has given up, and
behind a saturated database its commands keep loading, deciding and writing anyway.

The load test measured what that costs
([1 October](../load-test.md#1-october-the-checkouts-waste-and-then-hot-sku-shards)): 41,259 of the
second run's 48,744 holds were still held when the run ended — checkouts the store had stopped
waiting for while the ledger, behind the database, was still working on them. That is database work
done for nobody, on the database that is the bottleneck, and it slows every later request down: more
timeouts, more orphaned work, slower answers, more timeouts.

**This is not the deadline [ADR 4](0004-deadline-is-the-truth.md) is about.** That one is a hold's
fifteen minutes, decided by the kernel from a stored `expiresAt`, and it answers "may this commit?".
This one is a single HTTP call's budget, decided by `Till` before the kernel is ever invoked, and it
answers "is anyone still waiting for this?". A hold can be perfectly valid and well inside its own
deadline while the *request* that is trying to commit it has already been abandoned by its caller.

## Decision

**The caller tells the ledger how long it will still wait. The ledger does not apply a decision its
caller is no longer waiting for.**

**On the wire**, `TillClient` sends, on every attempt, `min(attempt timeout, what is left of the
call's deadline)` as `Till-Timeout-Ms: <non-negative integer>` — milliseconds, not an instant.
Relative rather than absolute so that clock skew between the store's host and the ledger's cannot
matter, for the same reason gRPC's `grpc-timeout` is relative rather than an absolute deadline: two
machines agree on a duration even when they disagree about the time. It is sent only when the call
was given a deadline (`TillClient.Builder.deadline`); `tillctl` and the console use the client
without one and see no change on the wire.

**In the ledger**, the command endpoints read the header and turn it into an instant on the server's
own clock: `till.clock().instant()` plus the budget (`Commands.deadlineFrom`). A missing header means
no deadline — `Till.execute(Command)` is unchanged and is what every caller without one still runs.
A header that cannot be a budget — not a non-negative integer, or one so large that adding it would
overflow what an `Instant` can represent — is a 400, in the existing problem shape for a bad request
(`IllegalArgumentException`, the same path a bad `ttlSeconds` already takes).

`Till` gets a second `execute`, `execute(Command, Instant deadline)`, that checks the deadline
**before every attempt's load** and **again after deciding, before `ledger.apply`**:

```java
for (attempt = 1; attempt <= maxAttempts; attempt++) {
    now = clock.instant();
    if (now >= deadline) throw new DeadlineExceededException(command, deadline);   // before load
    decision = decide(command, now);
    if (!decision.writes()) return decision.outcome();                             // a replay
    if (clock.instant() >= deadline) throw new DeadlineExceededException(...);      // before apply
    if (ledger.apply(decision)) return decision.outcome();
}
```

Two checks, not one, because the two failure modes are different sizes. The first catches a request
whose deadline had already passed before this attempt even started — cheap to catch early, and the
only check a database already behind would otherwise never get the benefit of. The second catches
the much narrower window where deciding took long enough, on its own, to run out the clock; without
it a slow `decide` on a saturated database could still walk into `apply` for a caller who left
during the decide. A replay (`!decision.writes()`) skips the second check because there is nothing
left to apply — the outcome was already decided and recorded, possibly on an earlier attempt, and
returning it costs nothing.

**What matters most is the second check**: when a command reaches `apply`, its caller is still
waiting. That is the one guarantee this change exists to add, and it is also the only one of the two
checks that touches the actual waste the load test found — a decision applied and then thrown away
by a caller who is gone.

A passed deadline is not a rejection. The kernel never got to decide, or decided but the decision is
being discarded unread, so there is no `Outcome.Rejected` to carry — `RejectionCode` is the kernel's
vocabulary for commands it considered and refused, and this is neither. Instead
`DeadlineExceededException` is a new core exception, shaped like `ConflictException`: it carries the
command and the deadline, and like `ConflictException` it is nobody's mistake. The server answers it
the same way it answers contention — 503, retryable — but with its own problem `code`,
`DEADLINE_EXCEEDED`, because the two causes call for different operator reactions (see
[`operations.md`](../operations.md)).

**No `Retry-After` on `DEADLINE_EXCEEDED`**, unlike `CONTENTION`'s `Retry-After: 1`. Two reasons,
either of which would be enough on its own:

1. `TillClient` does not read the header at all today — a 503 is retried on the client's own
   backoff schedule regardless of what the server suggests. Sending a value that nothing consumes
   would be decoration, not behaviour.
2. Even if something did read it, the client's own deadline bookkeeping (`left(started)` in
   `TillClient.send`) has, by construction, already run out of room by the time this response
   arrives: the server only reaches this code path when `till.clock().instant()` has passed an
   instant derived from *that same call's* remaining budget, measured from the same clock the client
   measured it from. Whatever clock skew and network time separate the two, the client's own
   `started`-relative timer will be at or past zero before or very shortly after the server's. A
   `Retry-After` would be telling a caller to wait inside a budget it has already exhausted.

## What this deliberately does not do

**The `Ledger` SPI and `JdbcLedger` are unchanged.** `apply` does not know about deadlines and does
not check one inside its write transaction. That means a decision can still be applied after its
caller's deadline if `apply` itself is what is slow: queuing for a pooled connection — bounded by
`spring.datasource.hikari.connection-timeout`, 2 s — or waiting on its own statements once it has
one. Closing that gap would mean `JdbcLedger` reading a deadline and comparing it against a clock
from inside a transaction it did not otherwise need a clock for, on every command, to defend against
a window the load tests have not yet shown to be large. That is a cost worth paying only once the
next number says it is.

So the gap is **measured instead of closed**. In `Commands`, after a command that was given a
deadline completes, if `till.clock().instant()` is already past that deadline, a counter
`till.late{kind, outcome}` is incremented — same tag values `till.outcome` already uses for `kind`
and `outcome`, a separate meter name rather than a third tag value on `till.outcome`, because adding
a tag to some of `till.outcome`'s samples and not others breaks Prometheus's rule that every sample
of one metric carries the same tag keys. The next load test says how often `till.late` fires, and
whether it tracks `till.outcome{outcome="deadline_exceeded"}` closely enough — meaning most of the
lateness is happening *after* the pre-apply check rather than being caught by it — to justify an
in-transaction check after all.

**Two things outside `Till.execute` are not bounded by either check, and no header can reach them:**

- **Time in the servlet container's queue, before the header is even read.** A request can sit in
  Tomcat's thread pool behind others for close to the whole budget the client thought it was
  spending on the attempt itself; the clock the deadline is checked against only starts running (on
  the server's side) once a handler thread reads the header, which is already some unknown distance
  into the budget the client sent.
- **A statement already running in PostgreSQL.** Once `apply`'s transaction has sent its statements,
  nothing this change adds will cancel them; the deadline check that matters already happened before
  `apply` was called, and what happens inside it is exactly the gap the paragraph above measures.

## Counting the refusals

`till.outcome{kind, outcome="deadline_exceeded"}`, next to the existing `outcome="exhausted"`
(`ConflictException`), so that `till_outcome_total` lets an operator tell overload — the caller's own
clock running out — from contention — the rows not staying still long enough.
[`operations.md`](../operations.md) has both in the symptom and alerting tables, and how to tell
them apart.

## Alternatives

**An absolute instant on the wire instead of a relative budget.** Simpler on the server — no
addition, nothing to overflow — and wrong the moment the two hosts' clocks disagree by more than a
few hundred milliseconds, which is not a rare condition across a load balancer and two or three
hops. A relative value makes the two clocks' difference irrelevant, at the cost of one addition on
the server and one subtraction on the client, which both already had a clock in hand.

**Checking the deadline inside `JdbcLedger.apply`'s transaction.** Closes the gap this change leaves
open — the one `till.late` measures — completely, at the cost of a deadline parameter and a clock
read on every write, in the one place optimistic concurrency already made deliberately cheap
([ADR 2](0002-optimistic-concurrency.md)). Rejected for now because nothing has yet shown the gap to
be worth that on every command rather than only the slow ones; `till.late` is what turns "nothing has
shown it yet" into a number the next load test can overturn.

**One check only, before `apply`.** Skips the load for a request that is already late, which sounds
like it should be enough — but a database already at 98% CPU benefits from *not loading* a doomed
request's snapshot at all, and the fourth load test's own finding was that the load itself (a
read-only transaction, four statements) was a meaningful share of the database's work. The pre-load
check is what lets an overloaded ledger shed doomed work before paying for the read, not only before
paying for the write.

**Reusing `ConflictException` and `"CONTENTION"` instead of a new type and code.** Would have cost
nothing to wire up and broken the one thing [point 4](#counting-the-refusals) exists for: an operator
reading `till_outcome_total{outcome="exhausted"}` rising reaches for `till.max-attempts` or
`tillctl shard`, neither of which does anything about a caller whose own clock ran out. The two
causes need to stay two counters precisely so the dashboard keeps telling them apart.

**A `Retry-After` on `DEADLINE_EXCEEDED`, for symmetry with `CONTENTION`.** Covered above: nothing
reads it today, and the caller's own deadline accounting will, by construction, already be at or
past zero by the time it would matter.
