# 14. A decision is written in one round trip

**Status:** accepted

## Context

`JdbcLedger.apply` wrote a decision the way a service method would: `BEGIN`, a statement for each
row, `COMMIT`. A reserve was seven statements — `BEGIN`, its stock row, its reservation, the
reservation's line, its event, its idempotency record, `COMMIT` — in six round trips, because the
driver sends `BEGIN` with the first of them; a commit or a release was six. Every one of those round
trips costs the database a protocol exchange, and every one after the first is made with the stock
row already updated, and so locked, by the first. The next command on that shard waits for the lock
until the `COMMIT` arrives.

The last load test recorded the ledger database's load with Database Insights. On a server with two
vCPUs and about seventeen backends busy at once on average, the load was 67% CPU, 11% WAL, and 16%
`Client:ClientRead`: a backend inside a transaction, waiting for the application's next statement,
holding the locks it has taken. By statement, `COMMIT` was 20% of the load, `BEGIN` 5%, the stock
update 14%, and the four inserts — outbox, reservation, line, idempotency record — about 21%
together.

The rows have to be written together, all or nothing ([ADR 6](0006-outbox.md)), and every update
has to find its row at the version the decision was made against
([ADR 2](0002-optimistic-concurrency.md)), or nothing of the decision may be written. What does not
have to be true is that the application holds a transaction open across the network while it does
so.

## Decision

`till_apply` is a PL/pgSQL function, added by `V4__apply_function.sql`, that takes a whole decision
and writes it. `apply` calls it once, in autocommit, so the statement that calls it is the
transaction: it begins, writes and commits inside one round trip, and no row the decision has
written stays locked while anything crosses the network.

- **A decision is arrays.** Each kind of row is a set of parallel arrays, one argument per column:
  `put_*` for the stock rows (`Mutation.PutStock`), `set_*` for state changes, `insert_*` for new
  reservations and `line_*` for their lines, one per shard a hold took units from, `event_*` for the
  outbox, and four scalars for the idempotency record. A decision with N stock rows, M lines and K
  events is arrays of N, M and K elements; one without a kind passes empty arrays, and one without a
  record passes nulls. The statement text is the same for every decision, so the driver prepares it
  once per connection, server-side once it has been used a few times, and PL/pgSQL keeps the plans
  of the function's own statements for the life of the connection. The arguments are named in the
  call, so the statement says which column each placeholder carries rather than depending on the
  order the function declares them in. Arrays of one kind that differ in length are a malformed
  call, and the function says so with SQLSTATE `22023` before writing anything: a missing element
  would read as null, match no row, and pass for contention.
- **It applies; it does not decide.** Every value it writes is one the kernel computed and every
  version it checks is one the kernel read; there is no arithmetic on a level in it, no reading of
  a row to decide anything, and no rule. It is the loop `apply` used to run, moved to the other end
  of the connection, and it writes in the same order, the order the kernel lists them in: the stock
  rows first, in SKU and shard order, so that two decisions never wait for each other in a cycle;
  then the state changes, the expirations of reclaimed holds before the command's own; the new
  reservations and their lines; the events, one at a time so that their sequence numbers ascend in
  the decision's order; the idempotency record last. The function can keep that order and no other,
  so `apply` refuses a decision listed in any other, with an `IllegalArgumentException` and before
  anything is sent, rather than writing it in an order nobody chose.
- **A refusal is an error the statement cannot survive.** An update that matches no row at its
  expected version, or an `on conflict … do nothing` insert that inserts nothing, makes the function
  raise SQLSTATE `TL001`. The statement fails, and in autocommit a failed statement takes everything
  it wrote with it: no reservation, no line, no event, no record. `apply` reads `TL001`, and only
  `TL001`, as `false`. `TL` is a class of SQLState PostgreSQL does not use, so no error of its own can
  be mistaken for a refusal: a check violation is still a `LedgerException` with its own message, and
  so is a unique violation on a line, which would mean a decision listing one twice.
- **Autocommit is the transaction.** A connection that is not in autocommit is switched into it for
  the call and back afterwards; in a transaction the pool had opened, the decision would be written
  and never committed. Hikari's connections are in autocommit, so `apply` makes no such call.

`JdbcSchema`, which runs the same migration files for tests and embedded use, split them at every
semicolon; it now splits around quoted strings, so that a dollar-quoted body, where the function's own
semicolons are, reaches the database whole.

The two designs weighed, side by side:

| | One statement of data-modifying CTEs | A PL/pgSQL function, called in one statement |
| --- | --- | --- |
| Statement text | fixed, with every kind of row as arrays; long, and every CTE planned and run on every call, empty or not | `select till_apply(...)`, fixed; the function's statements planned once per connection |
| N stock rows, M lines, K events | arrays, `unnest`ed into each CTE | arrays, a loop over each kind, the lines in one insert |
| ADR 1's boundary | applies, in SQL | applies, in PL/pgSQL; the same loop as before |
| A row at the wrong version | its update matches nothing, and the other CTEs write regardless: something has to make the statement fail, and plain SQL has nothing that raises | `raise` with SQLSTATE `TL001`, at the first refusal, as the transaction it replaces stopped there |
| Lock order | a set-based update locks rows in its plan's order | the decision's order: stock by SKU and shard, as before |
| What it needs | a raise from somewhere: a function after all, or an error borrowed from something else | a migration, and `JdbcSchema` learning dollar quotes |

## Consequences

**One statement on the wire, measured at the connection.** `aDecisionIsOneStatement` in
`JdbcLedgerTest` wraps the pool and records every statement the driver executes, every row of a
batch counted, and every `setAutoCommit`, `commit` and `rollback`: a reserve and the commit after it
are one statement each, with nothing around them. Against the old `apply` the same test recorded five
statements for the reserve, between `setAutoCommit(false)` and `commit`. `aStaleDecisionWritesNothing`
refuses a reserve of two SKUs whose second stock row moved after the first was written by the same
statement, and finds neither row changed and no reservation, line, event or record;
`aCheckViolationIsAnException` adds a constraint the kernel knows nothing about and finds the
violation raised, nothing written. Each failed first against the old `apply` for the number of
statements; the assertions about what was written were checked by breaking the new code on purpose —
a stale row answered with `return` instead of `raise` left the first stock row written, and reading
every SQLState as a refusal swallowed the check violation. Five more tests reach the function's other
refusals — a stock row created first, a reservation id taken, a reservation whose version moved, an
event already in the outbox, an idempotency key claimed — each after a stock row the same statement
had written, and each failed when that refusal's `raise` was taken out.
`aDecisionIsCommittedOffAutocommit` lost its write when `apply` stopped switching a connection into
autocommit; `aMalformedCallIsNotARefusal` read `TL001` until the length check existed;
`aDecisionOutOfOrderIsRefused` found a decision listing a new reservation before a state change
applied, in the function's order, until `apply` refused it.

**The differential test and the concurrency test pass unchanged**: six seeds with and without shards,
row for row and outbox sequence for outbox sequence against the in-memory ledger, and four suites of
real threads. That is the evidence that no decision is written differently, only in one statement.

**Measured: the checkouts a second rose by two fifths.** `ContentionBenchmark` with
`-Dbench.dbcpus=2 -Dbench.shards=16`, thirty seconds a run, six pairs interleaved against the commit
this branched from (`6069d4b`), each pair in the other order from the last, from a second checkout of
that commit removed afterwards. Both sides ran this branch's benchmark, which now counts the
statements sent at the connection, `BEGIN`, `COMMIT` and `ROLLBACK` included:

| | Checkouts a second (six runs) | Mean | Spread | Decisions that conflicted | Statements a checkout | Transactions a checkout |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Before | 883.0, 1,022.2, 1,050.6, 1,018.7, 979.5, 993.3 | 991.2 | 17% of the mean | 6.6–6.8% | 15.5–15.6 | 4.3–4.4 |
| After | 1,372.8, 1,361.5, 1,488.4, 1,379.0, 1,359.5, 1,368.0 | 1,388.2 | 9% | 5.4–5.6% | 4.1 | 4.3 |

+40% on the mean, and not inside the noise: in every pair the run after the change was faster, by 33%
to 55%, and the slowest of the six after it was faster than the fastest of the six before. A first
round of six pairs, against an earlier revision of this change that wrote the same rows, came out at
+42% (813 to 1,154), every pair faster by 29% to 68% — with the laptop busier then, its load average
up to 11.5 with other work on it. The database container was CPU-bound on both sides: `docker stats`,
sampled every five seconds through every run, averaged 181% of its 200% before and 189% after. So
checkouts a second follow what a checkout costs the database, and a checkout is four statements
instead of fifteen and a half. What the statements it no longer sends saved is the work every
statement costs the server whatever it writes: a protocol exchange, a transaction begun or ended, an
executor set up and torn down. The median checkout fell from 57–63 ms to 18–21 ms and the p99 from
212–302 ms to 190–204 ms, and fewer decisions conflicted.

What this benchmark cannot show is what Database Insights saw as `Client:ClientRead`. Its database
runs on the same laptop as its callers, and Testcontainers starts PostgreSQL with `fsync=off`, so a
commit costs no flush. Between the ledger's tasks and RDS a round trip crosses a real network, and a
reserve used to make six of them with its stock row locked: the next load test is what measures
that.

**A refusal is an error in the database's log.** PostgreSQL logs every error, so each refused apply
writes an `ERROR` line, a `CONTEXT` line naming the function and its arguments' types, and a
`STATEMENT` line with the call: 1,132 bytes on `postgres:17-alpine` with its default line prefix. The
old `apply` answered a moved row with a `ROLLBACK`, which logs nothing. At the load test's conflict
rate it is a steady trickle: with sixteen rows a game the load test rolled back 14,748 transactions
in its ten-minute window, about 25 a second, which at 1.1 KB is about 100 MB of log an hour. With one
row a game it rolled back 220,863, which would be 1.5 GB an hour, and RDS keeps its logs, three days
of them by default, on the instance's own storage: twenty gigabytes on the load test's.
`log_min_error_statement` above `error`, in the parameter group, drops the `STATEMENT` line, about half
of it. Catching the refusal inside the function would keep it out of the log, and costs more than it
saves (see below).

**`pg_stat_statements` sees one statement, and not a refused one.** At its default `track = top` a
decision is one `select till_apply(...)`; `track = all` shows the statements inside it. A statement
that raised is not recorded at all, so the time refused applies take is no longer in its totals.

**The function deploys before the code that calls it.** Flyway applies `V4` when the service starts,
before it serves anything, and code that does not call the function is unaffected by it, so a rolling
deploy is safe in both directions. Changing its arguments later is a new migration that adds a new
function, or a new overload, beside the old one: during a rolling deploy the old instances still call
the old one.

**What it does not change.** One commit per decision, and the WAL flush each one waits for: the 20% of
the load that was `COMMIT` included that wait, which is now inside the one statement rather than gone.
Transactions per checkout, which PostgreSQL counts the same for one statement in autocommit as for a
transaction of seven ([ADR 12](0012-one-statement-snapshot.md) found the same). The kernel, the
in-memory ledger, and the way a snapshot is read.

## Alternatives

**One statement of data-modifying CTEs**, the mutations `unnest`ed from arrays. Every CTE in a
statement runs against the same snapshot and none sees another's effects, and an `UPDATE` that matches
no row is not an error. Tried on PostgreSQL 17: a stale stock update beside an outbox insert matched
nothing, and the event was inserted and committed anyway. The statement has to be made to fail, and
plain SQL has nothing that raises: it takes a function — a migration anyway — or an error borrowed
from something else, a division by zero that the code would then read as contention and that a real
division by zero, in some later edit, would be read as too. Beyond that, a set-based `UPDATE … FROM
unnest(…)` locks rows in whatever order its plan visits them, not in SKU and shard order, which brings
deadlocks between decisions that share rows; and every CTE runs to completion before the statement
can fail, so a refused decision does all of its writes, taking all of their locks, first.

**A function that catches its own refusal** (`exception when sqlstate 'TL001' then return false`),
which keeps refusals out of the log. A block with an exception handler starts a subtransaction every
time it is entered, which is every apply, and a subtransaction that writes takes a transaction id of
its own — two a decision instead of one, and anti-wraparound vacuums twice as often. A refusal found
once anything has been written or locked then commits a transaction that holds an id, and waits for
the WAL flush that a rollback does not.

**A procedure that rolls back** (`call till_apply(…)`, a `rollback` inside it, `applied` as an `out`
argument): no error, no subtransaction, no flush on a refusal — the closest to what the old `apply`
did. Rejected on cost. A `call` runs as a utility statement in a non-atomic context, and with pgbench,
one client, against a container limited to two CPUs, applying a reserve's five rows took 0.32–0.33 ms
a call as a procedure and 0.27 ms as a function, with or without an exception block: about 50 µs more
for every decision, to save a log line on the one in twenty that is refused. It also only works at the
top level: inside a transaction block its `rollback` is an error.

**Keeping the transaction and pipelining its statements.** Sending them without waiting for each
answer still leaves `COMMIT` or `ROLLBACK` to be decided by those answers: two round trips at least,
with the locks held across the second.
