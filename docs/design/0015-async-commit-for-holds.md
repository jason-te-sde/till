# 15. A hold commits without waiting for the disk

**Status:** accepted

## Context

Database Insights, recording the ledger's database during the last load test, put `COMMIT` at 20% of
its load, and the waits on the write-ahead log — `LWLock:WALWrite`, `LWLock:WALInsert`, `IO:WalSync` —
at about 11% more. Since [ADR 14](0014-one-round-trip-apply.md) a decision is one call to `till_apply`
in autocommit, and every one of those calls waits, inside the statement, for its WAL to be flushed to
disk before its connection is free again. ADR 14 said as much: the flush each commit waits for moved
inside the one statement; it did not go away.

Most of those commits are holds: a reserve, a release, the sweep writing off expired holds, a command
short of stock writing them off on its way. A hold is temporary by design. It expires at its deadline
whether or not anything has written that down ([ADR 4](0004-deadline-is-the-truth.md)), and a customer
who loses one can take another.

PostgreSQL lets each transaction choose whether to wait. With `synchronous_commit` off, a commit
returns once its WAL record is in the server's buffers, and the WAL writer flushes it within three
times `wal_writer_delay`: 600 ms at the default of 200 ms. A crash of the server inside that window
loses the commits not yet flushed — whole, and only from the end. Recovery replays the WAL in order,
up to the last record that reached disk, so the database comes back as it stood at an earlier
instant, as though the lost transactions had rolled back. A commit that does wait flushes all the WAL
written before its own, everybody's, not only its own transaction's.

## Decision

**A decision whose outcome is a hold taken (`Reserved`), a hold given back (`Released`), expired holds
written off by the sweep (`Swept`), or a refusal (`Rejected`) commits with `synchronous_commit` off, for
its own transaction only. Every other decision waits for the disk, as before.**

- `V5__apply_durability.sql` replaces `till_apply` with V4's function and one more argument,
  `durable`, last. When it is false the function runs `set local synchronous_commit to off` before it
  writes anything. `set local` lasts until the transaction ends, and in autocommit the transaction is
  the statement, so the next statement on the connection commits as it did before. V4's version is
  dropped: there is one `till_apply`.
- `JdbcLedger.apply` passes `durable` from the decision's outcome, against an explicit list of the four
  kinds that may go without (`ASYNCHRONOUS`). Anything not on the list waits, so an outcome added later
  waits for the disk until somebody decides otherwise — and a test fails until somebody does.
- `durable` is true by default. A rolling deploy applies V5 when its first new instance starts, while
  instances of the last version are still serving, and they send V4's call, which names every argument
  but this one. With the default, that call reaches the one function and commits durably, as it always
  did. Without it, every write of theirs would fail with "function does not exist" until they were
  replaced; with V4's function left beside V5's, with "not unique". Rolling the code back is safe for
  the same reason: V5 stays, and the old call still works.
- The outbox publisher flushes the WAL between reading a batch and sending it, so that no event leaves
  while a crash could still take back the decision that wrote it (below).

## Why a crash cannot make it wrong

A crash loses some of the asynchronous commits of the last few hundred milliseconds, and no other
commit. Three things make that safe.

**What a crash loses, it loses whole, and from the end.** A transaction is in the replayed WAL, all of
it, or not at all: its stock rows, its reservation and lines, its events and its record together. The
database never comes back with a hold's stock row moved and its reservation missing, which is the
inconsistency the invariants would catch.

**Nothing durable survives without what it depended on.** A decision that read a hold's rows was made
after that hold committed, so its own commit record comes later in the WAL, and a durable commit
flushes everything before it. A sale acknowledged to the store has the hold it sold on disk with it. A
crash can lose a hold and every later asynchronous decision built on it, together, and never a hold
under a decision that survives.

**Nobody acts on a hold before the disk has it, except its caller.** An asynchronous commit is
visible to every other connection the moment it returns, before it is flushed; a synchronous one
becomes visible only after its flush. So what matters is who reads a hold in that window, and what
they do with it:

- *The caller* is told `Reserved` or `Released` before the disk has it. That is the trade, and the next
  two sections are what it costs.
- *The publisher* read a batch, sent it and only then marked it, so it could have sent a hold's event
  to the broker and the hold been lost behind it. The store's projection applies a reservation event as
  a delta, and only an adjustment, which carries absolute levels, repairs drift: a `reserved` event for
  a hold the ledger no longer has would have left that SKU's availability too low until the next
  adjustment, and a lost release's event, followed later by the hold's real expiry, would have given
  its units back twice. ADR 6's promise that an event never describes a change that did not happen
  would have held for the table and not for the stream. So the publisher now flushes the WAL after
  reading a batch and before sending it — a logical message that nothing decodes, written outside the
  transaction and flushed at once (`pg_logical_emit_message(false, 'till.outbox', '', true)`), which
  flushes everything written before it, the commit of every row just read included. An event is sent
  only once the commit that wrote it is on disk, as it was when every commit waited.
- *Another decision* can read a hold that a crash then loses. If it is durable, its own flush keeps the
  hold. If it is asynchronous, the two are lost together, or the later one alone. The one trace a lost
  hold can leave in somebody else's answer is a refusal: a customer told there is not enough stock
  because of a hold that, after the crash, never existed. Asking again finds the stock.

Reads through the API — `GET /v1/stock/{sku}`, `GET /v1/reservations/{id}`, the operator's view of the
outbox's tail — can show a hold that a crash then takes back. They are views; nothing is decided from
them.

Outcome by outcome:

| Outcome | Commits | What a crash that loses it leaves |
| --- | --- | --- |
| `Reserved` | without waiting | a hold that never happened: the stock as it was, and the reservation, its lines, its event and its record gone together. A later commit or release of it is answered 404 `RESERVATION_NOT_FOUND`. No unit is sold twice |
| `Released` | without waiting | the hold still there, which expires at its deadline like any other: its units come back then rather than at once. The release's record is gone, so asking again releases it again |
| `Swept` | without waiting | holds still stored as held past their deadlines, which every decision already treats as expired (ADR 4). The next sweep, or the next command short of their stock, writes them off again |
| `Rejected` | without waiting | no record of the refusal, so the same key asked again is decided afresh rather than replayed; and any expired holds it wrote off on the way, as for `Swept`. A refusal never moves stock: the kernel writes nothing with one but those expiries and its record |
| `Committed` | waiting for the disk | — |
| `Adjusted` | waiting for the disk | — |
| `Sharded` | waiting for the disk | — |

## What stays durable, and why

- **A hold turned into a sale (`Committed`).** The store tells the customer the order is paid. Lost, the
  sale would leave its hold in place, and the hold would expire or be released and put its units back
  on sale: units paid for once, and sold again. This is the commit the ledger exists to keep.
- **On-hand stock adjusted (`Adjusted`).** A lost delivery is stock the warehouse has and the ledger
  does not count; a lost write-off is stock the ledger sells and the warehouse does not have. Either
  way the count stops matching the shelf, and nothing will notice.
- **A SKU split into shards (`Sharded`).** A lost split would leave the SKU in its old rows with its
  total unchanged, and harm nothing. It waits because it is rare — an operator's decision, made before
  a sale — so there is nothing to gain, and an operator told a SKU is split should find it split.
- **Anything added later**, because the list is of what may go without, not of what must wait.

## What a lost hold looks like to a customer

From the store's code (`OrderService`, its `Problems`) and the storefront's (`HeldOrder`, `problem.ts`),
neither of which this changes:

The customer placed an order; the ledger answered `Reserved`; the store wrote the order, pending, with
the hold's id and deadline. Then the ledger's database crashed and came back without the hold. The
order page still counts down — "Your copies are held for" — under a Pay button. Pay asks the ledger to
commit the hold, and the ledger answers 404 `RESERVATION_NOT_FOUND`. The store does not branch on that
code: it passes the refusal on as its own 404, code `RESERVATION_NOT_FOUND`, title "No such
reservation", detail "no reservation" and the hold's id, and leaves the order pending. The storefront
shows the detail as a sentence — "No reservation" and the id — with no "Try again", because a 404 is
not something a retry fixes; Pay pressed again sends the same key and gets the same answer. Cancel
gets the same 404. When the countdown runs out the order shows as expired, the store's ordinary way of
saying a hold ran out, and the customer can place the order again. No money moves, since the store is a
demonstration and says so under that button, and no unit was sold twice: after the crash the ledger's
stock is as it would be had the hold never been taken.

It is not a good message: a page counting down a hold, and a sentence saying there is none. The store
could recognise `RESERVATION_NOT_FOUND` on a pending order, close the order and say the hold was lost.
That is a change to the store, and not this one.

A lost release is invisible to the customer: the store marked the order cancelled when the ledger
answered, and the copies come back on sale at the hold's deadline instead of at once. A lost expiry is
invisible too.

## Consequences

**Fewer commits wait for the disk.** In a checkout, the reserve no longer waits and the payment still
does. Cancellations and the sweep no longer wait at all. How much of the 20% and the 11% that is, the
next load test on AWS will say, in `COMMIT`'s share of the load and in the WAL waits. The local
benchmark cannot: Testcontainers starts PostgreSQL with `fsync=off`, where a flush costs nothing, so
there is nothing there for this change to save, and no number is given for it.

**What loses holds is the server dying, not stopping.** A clean shutdown flushes the WAL first and
loses nothing. The last few hundred milliseconds of holds go when the instance fails, the operating
system kills PostgreSQL, or the power goes.

**A refusal can be forgotten.** Its record commits without waiting, so a crash can lose it, and a client
that asks again with the same key afterwards is decided again rather than answered from the record: a
refusal for want of stock can become a hold, if the stock is there by then. A refusal wrote nothing
else that a second decision could contradict.

**The publisher writes to the WAL once a batch**: a message of a few dozen bytes, and the flush it
forces, which the WAL writer or a durable commit has often done already. The `flush` argument is
PostgreSQL 17's. It flushes the server's own WAL and does not wait for a synchronous standby, which
neither deployment has; on a server with one it would not be enough, and the publisher would need a
durable commit between reading a batch and sending it.

**Tested, each test watched failing first.** `aDecisionThatNeedNotBeDurableTurnsSynchronousCommitOff`
finds `show synchronous_commit` off inside the transaction of a call told the decision need not be
durable, on again in the next transaction, and on after one told it must be; it failed against V4,
which had no such argument. `theLastVersionsCallIsStillAnswered` sends V4's call, typed as `JdbcLedger`
bound it, and finds it answered and committed with the setting on; it failed against V5 without the
default, and again with V4's function left in place. `onlyHoldsAndRefusalsCommitWithoutWaiting` applies
a decision of every kind of `Outcome`, the kinds read from the sealed interface so that a new one fails
until it is classified, and reads the setting through a trigger inside the statement that writes each
decision's record; it failed against the old `apply` with every kind on, and again with `Swept` left off
the list. `anEventLeavesOnlyOnceItIsDurable` commits an event the way a hold is committed, checks that
it is visible and not yet flushed, and records at the moment of the send whether the server's flush
position has passed it; it failed against the old publisher, and again with the flush moved after the
send or written without `flush`. It slows the WAL writer to one flush in ten seconds while it runs
(`ALTER SYSTEM`, reset afterwards): at the default, the WAL writer flushed the event by itself before the
send often enough that the test passed against the old publisher in three runs out of six.
`JdbcDifferentialTest` and `JdbcConcurrencyTest` pass unchanged.

## What is not done

- **No benchmark**, for the reason above. A number from a database that does not flush would mean
  nothing.
- **No test crashes the database.** The suite proves that the setting reaches exactly the holds and the
  refusals, for their own transaction, that the last version's call still works, and that the publisher
  sends nothing that is not on disk. That a crash loses only the end of the WAL, each transaction whole,
  is PostgreSQL's guarantee, and not something this repository tests.
- **Not run on Aurora.** There, `synchronous_commit` off returns before the storage acknowledges the
  write rather than before a local flush, and the publisher's flush goes to the storage as well; neither
  has been exercised. The first deployment on Aurora should watch that the outbox drains.
- **`commit_delay`**, which makes concurrent commits share a flush rather than skip it, was not tried.
  It is a setting in the parameter group, not code, and it would apply to the commits that still wait.
- **The store** is unchanged, and reports a lost hold as "No reservation", above.

## Alternatives

**`synchronous_commit` off for the whole database, or for the ledger's role.** One parameter, no
migration, and every commit faster — the payments and the adjustments included, which are exactly the
commits a crash must not lose.

**A second connection pool whose connections run with it off, for holds.** Twice the connections, on a
database whose connection budget is already the constraint ([operations](../operations.md)), and every
statement on those connections asynchronous whether it was meant to be or not.

**`SET LOCAL` from Java before the call.** It needs a transaction around the call — `BEGIN`, the `SET`,
the call, `COMMIT` — which is the round trips ADR 14 took away. Inside the function it costs nothing.

**For the publisher:** a commit, which would flush, and give up its claim, a transaction-scoped advisory
lock, in the middle of a batch; a durable write on a second connection, which works on any version and
does wait for a standby, at the price of a table that exists to be written and a second connection for
every batch; waiting until `pg_current_wal_flush_lsn()` passes the position the read saw, which polls;
and sending only rows older than the WAL writer's delay, which is a hope rather than a guarantee.
