# 16. Commands that wait together are decided together

**Status:** accepted

## Context

A load test of 8,000 shoppers with the ledger on a `db.t4g.micro` of its own, after
[ADR 15](0015-async-commit-for-holds.md), ran that database at up to 89% CPU. Database Insights put
about eleven sessions active on its two vCPUs, two thirds of the load on the CPU, and of that
`till_apply` 73% and the snapshot statement 18%. The scenario is a closed loop: every speed-up lets
shoppers order sooner, which brings the database back to saturation. Unconstrained, the 8,000 would
place about 136 orders a second and pay for 128; the ledger served 108 and 86. For a checkout's p99
to fall under a second, the ledger needs about twice the capacity it has.

[ADR 14](0014-one-round-trip-apply.md) wrote a decision in one statement instead of a transaction of
seven, wrote exactly the same rows, and the checkouts a second rose by two fifths. So a large share of
what a decision costs the database is what any statement and any transaction cost — a protocol
exchange, an executor set up and torn down, a transaction id, a commit — rather than the rows it
writes. And the work to share those costs over is already waiting: each ledger task had about 180
requests queued for its sixteen connections throughout the last runs
([load test](../load-test.md#1-and-2-october-work-for-nobody-and-then-the-store-on-a-server-of-its-own)).

## Decision

**Commands waiting at the same time are decided together, against one snapshot, and written together,
in one statement, with exactly the answers they would have had one at a time in the order they
arrived.**

### `Till.executeAll`

`executeAll(List<Call>)` takes commands, each with its caller's deadline or none, and returns an
`Answer` for each, in the same order: the outcome, or the exception `execute` would have thrown. It
works through the list in **runs** of consecutive commands. For a run, it:

1. reads the clock once, and answers `DeadlineExceededException` for every command whose deadline has
   already passed, leaving it out ([ADR 11](0011-request-deadlines.md));
2. loads one `BatchSnapshot` for the rest, through `Ledger.loadBatch`: each command's idempotency
   record, each reservation a command names with its lines, and every shard of every SKU a command
   names or its reservation holds — what `load(command, now, 0)` reads for each of them, read once, at
   one instant;
3. seeds an `InMemoryLedger` with exactly those rows, at their versions (`InMemoryLedger.from`), and
   takes each command through the loop's steps against it, in order: load its snapshot from it, decide,
   apply the decision to it. Each command sees what the ones before it did, and a key used twice in
   one batch is executed once and replayed once, as it would be one at a time;
4. folds the decisions into one `BatchDecision`, the net change: each stock row once, at its final
   values and at the version it would have reached one at a time, checked against the version the
   batch read; each reservation the batch created once, in its final state; each state change of a
   reservation the batch read, checked against the version read; every event, in order; every
   command's idempotency record;
5. writes it with `Ledger.applyBatch`, all of it or none of it.

A refusal — a row moved, a key taken — loads the run again and decides it again, up to
`till.max-attempts` times, with no wait between, as `execute` does ([ADR 2](0002-optimistic-concurrency.md)).
When they run out, every command whose decision wrote something is answered `ConflictException`. A
replay is still answered with its outcome: it wrote nothing that could have conflicted.

The deadline is checked again **before the write**, as `execute` checks it before `apply`. A command
whose deadline passed while its run was loaded and decided is answered `DeadlineExceededException`,
and the run is decided again without it, from the same snapshot and in memory: a command after it is
then decided as if it had never been there, which is what one at a time would have done with a
decision its caller had stopped waiting for.

Two kinds of command are not decided in a run, and go through `execute` on their own:

- **A command whose decision is short of stock**, while the reclaim limit is above zero. `execute`
  would load the expired holds standing in its way and decide again
  ([ADR 4](0004-deadline-is-the-truth.md), "Later"); the batch's snapshot does not have them, and
  cannot have them without bringing them to every command, which is the cost ADR 4's later note took
  away.
- **A sweep**, which loads what it finds rather than what it names.

Such a command **ends the run where it stands**: what came before it is written, it runs on its own,
and the next run starts after it with a snapshot of its own. It does not wait until the end of the
batch, because that would decide it after commands that arrived later, and with expired holds on a
SKU the two orders give different answers. Three units available, an expired hold of four on them,
and a batch of *B*, a hold of five, then *C*, a hold of three: one at a time, *B* is short, writes off
the expired hold, takes five of seven, and *C* finds two of the three it wants and is refused; with *B*
moved after the batch, *C* takes the three, and *B*, with the expired hold written off, finds four of
the five it wants and is refused instead. Ending the run keeps the answers those of arrival order, at the cost of a second
load and a second write when a shortfall falls in the middle of a batch. The load test stocks every
game with a billion units, and is never short.

A failure that is not a refusal fails the commands it reached: a load that throws answers every
command of its run with that exception, and a write that throws answers every command whose decision
it was writing. One command's decision throwing — an `IncompleteSnapshotException`, which is a bug —
answers that command alone, and the others go on.

### The ledger

The `Ledger` contract gains two methods, and both ledgers implement them.

- **`loadBatch(List<Command>)`** reads a `BatchSnapshot` at one instant. `InMemoryLedger` reads it
  under its lock. `JdbcLedger` reads it in **one statement, in autocommit**, built like the lean
  snapshot statement ([ADR 12](0012-one-statement-snapshot.md)) with an array in place of each scalar:
  the records of `idem_key = any(?)`, the reservations of `id = any(?)`, the lines of all of those
  (`reservation_id = any(?)`), and the stock of `sku = any(? || array(select sku from target_lines))`.
  Unlike the lean statement, it is **planned for its own arrays on every call** and never prepared on
  the server. A plan made without them guesses ten keys an array, and on tables that were small when
  it was made, reading them costs less than ten lookups: in the contention benchmark, a connection
  that settled on that plan early in a run went on reading every record and every reservation for each
  batch after the tables had passed a hundred thousand rows, about 16 ms a load instead of 0.1, and
  the run's checkouts fell from about 4,400 a second to 600. Planning costs a fraction of a
  millisecond, once a batch. `JdbcLedger` sets this through pgjdbc's `PGStatement`, the one driver
  interface the module compiles against.
  A `BatchSnapshot` refuses a reservation whose SKUs it does not hold, and `executeAll` refuses to
  decide a command whose SKUs it does not hold, with `IncompleteSnapshotException`: a SKU the adapter
  forgot must not look like a SKU that was never stocked (ADR 1).
- **`applyBatch(BatchDecision)`** writes a batch's net change, all of it or none of it, and refuses
  it — `false` — when any row it writes is not at the version the batch read or any key it inserts is
  taken. `JdbcLedger` writes it with **one call to `till_apply`**, in autocommit.
  `V6__apply_batches.sql` replaces the function with V5's and these changes:
  - **`put_new_version`**: the version a stock row is written at. A row three commands of a batch
    wrote is written once, at its final values, and its version moves by three, as one at a time
    would have moved it. Without one, it moves by one, as before.
  - **`insert_version`**: a reservation created and committed in one batch is inserted committed, at
    version 1, as creating and then committing it would have left it.
  - **`records_key`, `records_fingerprint`, `records_outcome`, `records_recorded_at`**: an idempotency
    record for each command, inserted in order, each refused like the single one if its key is taken.
  - **`durable`** is true when any command in the batch has to wait for the disk: a batch with a sale
    in it commits as a sale does.
  - The scalar `record_*` arguments now default to null and every new argument has a default, so the
    calls of V5 and of V4 are answered as they were, during a rolling deploy and after a rollback: one
    `till_apply`, as V5 left it ([ADR 15](0015-async-commit-for-holds.md)).
  - `apply(Decision)` sends the same statement, as a batch of one. One statement text, prepared once
    per connection, whatever the call.

### The server

`Commands.run` hands each command to a **batcher**: a bounded queue, and one worker thread that takes
whatever has queued, up to `till.batch.max-size` (64), runs it through `executeAll`, and gives each
caller its own answer. It does not wait for a batch to fill: it takes the first command as soon as
there is one, and then what else is already there. So batches form only while the worker is busy with
the last one — that is, under load — and a request on its own waits for nothing but the batch already
in flight. When the queue is full (`till.batch.queue-capacity`, 1024) a command is refused at once with
the existing 503 `OVERLOADED`, before anything reaches the database. The bound is well above the 200
requests the servlet container serves at once, so an ordinary burst never reaches it; a command that
waits too long in the queue is answered by its own deadline (step 1), also without reaching the
database. `till.batch.enabled` turns it off, and every command goes through `execute` as before.

Each request keeps its own HTTP semantics: its own outcome, status, idempotency behaviour and problem
body, and its own count in `till.outcome` and `till.late`. The batcher adds `till.batch.size`, the
commands in each batch, as a distribution; `till.batch.conflicts`, the batch writes refused because a
row had moved; and `till.batch.refused`, the commands turned away by a full queue.

### One writer

With two ledger tasks, each with its own worker, batches from the two would meet on the same rows
constantly: a batch of 64 commands over 32 games writes most of the load test's 512 stock rows. The
load test therefore runs **one ledger task, of two vCPUs and 4 GB** (`infra/loadtest.tfvars`). Several
tasks stay correct, because every row a batch writes is still checked against the version it read;
they only conflict more.

## Why the answers are the same

One at a time, a command is decided against a snapshot and written if nothing it writes has moved
since. A batch is decided against one snapshot, each command against that snapshot as the commands
before it left it, and written if nothing any of them writes has moved since. When the write succeeds,
the database holds what the commands would have left one at a time, in that order, starting from the
snapshot; and since nothing the batch wrote moved between the snapshot and the write, that is also what
they would have left starting at the write. Every command of the batch was waiting from before the
snapshot until after the write, so an instant inside its own request exists for each of them, in
order — the batch is linearizable exactly as its commands one at a time are.

What a command reads and does not write — the shards of a SKU its hold did not take from, a record it
replays, the stock a refusal found short — is read at the snapshot's instant. That is what one at a
time does with what it reads and does not write: a refusal is true at the instant its snapshot was
read, and so is the availability it reports. The batch makes the window between that instant and the
write no longer than a decision's own.

## Consequences

**A checkout costs a fraction of a statement.** A batch of *n* commands is two statements, each its
own transaction, where one at a time they were 2*n* of each; under load, *n* is whatever queued while
the last batch was in flight. A stock row many commands of a batch wrote is written once.

**One session writes for each ledger task.** The worker sends one statement at a time, so one ledger
task keeps at most one of the database's sessions busy with commands — the sweeper and the outbox
publisher have their own. A batch is far cheaper per command than a command alone, so this is not the
ceiling it sounds like, but it is a ceiling: a second core on the database does nothing for the
commands of one ledger task.

**A lone request waits for the batch in flight.** At a trickle, a command arriving while the worker is
writing waits for that write before its own load starts, where before it would have taken a
connection of its own. Two round trips at most, and none when the worker is idle.

**A batch that stalls holds up every command behind it.** One worker writes for the task, so a batch
that does not finish — a statement that does not return, a connection that does not answer, a
process that is not running — is a wait for every command queued behind it, where one at a time it
held up one connection's. The first load test with batching found the worker stopped for six to
eight seconds at a time, every minute or so, with the database idle and the task's CPU at 12%, and
the orders that waited it out were its order p99 of five seconds. A watchdog looks at the worker
several times a `till.batch.stall-after` (1 s): a batch running for longer is counted in
`till.batch.stalls` and logged once, with where the worker is, and a look that comes much later than it
was due — the whole process held up, not only its worker — is counted in `till.batch.pauses`.

**One command's fault fails its batch.** A write that the database refuses for a reason other than a
moved row — a check violation, which is a bug ([ADR 2](0002-optimistic-concurrency.md)) — answers every
command it was writing with the exception, where one at a time it would have failed only the command
that caused it.

**A refused batch decides every one of its commands again**, not only the one whose row moved, and
PostgreSQL logs one `TL001` error for it, as for a refused decision ([ADR 14](0014-one-round-trip-apply.md)).

**The answers do not change**, and the suite says so: for random histories, running them through
`executeAll` in batches of random sizes gives the same answers and the same final state, row for row
and version for version, as running them one at a time, on `InMemoryLedger` and on `JdbcLedger`.

## Alternatives

**A shortfall run after the batch, rather than in its place.** One load and one write fewer for each
shortfall in the middle of a batch, and different answers, in the example above.

**Several workers.** More of the database's cores in use, and batches from the workers contending for
the same rows, as two ledger tasks' would. Not tried.

**Waiting a few milliseconds for a batch to fill.** Larger batches at low load, and a delay on every
request at low load, when batching saves least.

**Batching the writes only**: each command loaded and decided on its own, the decisions written
together. Half the statements saved at best, and a decision made against a snapshot that does not
include the decisions written with it would have to be checked against them, row by row.

**`commit_delay`**, which makes concurrent commits share one flush. It shares a flush, not a
statement or a transaction, and the commits it would share are the durable ones, which ADR 15 already
made the minority.
