# 12. One statement for a snapshot that reclaims nothing

**Status:** accepted

## Context

A load test against a `db.t4g.micro` — two burstable cores, the largest the account's plan allows —
found the database at 98% CPU with every statement queueing for it
([load test, 1 October](../load-test.md#1-october-the-checkouts-waste-and-then-hot-sku-shards)). Among
the statements it was spending that on: `set transaction isolation level repeatable read, read only`,
a statement that reads nothing, averaged 9.18 ms over 99,710 calls — 10% of the database's statement
time. A snapshot cost four round trips and five protocol commands: `BEGIN` (piggybacked onto the
first real statement), the `SET`, a select of the idempotency record, a select of the stock rows, and
`COMMIT`. A commit or release reads more: the reservation and its lines too.

The transaction existed for one reason — [ADR 1](0001-pure-kernel.md) and [ADR 2](0002-optimistic-concurrency.md)
both say so — so that those reads see one instant. But that is a property of PostgreSQL's isolation
levels generally, not of a multi-statement transaction specifically: at **read committed**, which is
the default and what every other connection in this application runs at, a single statement already
sees one instant, and every subquery, CTE and `union all` arm inside that one statement shares it.
`SET TRANSACTION ISOLATION LEVEL REPEATABLE READ` buys nothing a single statement does not already
have, for a load that fits in one.

Not every load fits. A load that reclaims expired holds (`reclaimLimit > 0`) decides what to read next
— which holds are standing in a SKU's way — from what an earlier read found, and the sweep does not
know which rows it will touch until it asks. Those loads are rare: only a command that would otherwise
be short of stock reclaims at all (`Till.decide`), and a shortfall is the uncommon case while there is
stock to sell.

## Decision

Split `JdbcLedger.load` on `reclaimLimit`:

- **`reclaimLimit == 0`** — the lean first decision of every reserve, adjust, shard, commit and
  release — reads its snapshot with **one statement, in autocommit**: no `SET`, no `BEGIN`, no
  `COMMIT`. The statement is a `union all` of four arms, discriminated by a `kind` column, because
  PostgreSQL needs every arm of one `union all` to agree on column count and type and the four reads
  are genuinely different shapes:
  - the idempotency record, if the command carries a key;
  - the named reservation's header, if it names one;
  - that reservation's lines;
  - the stock of every SKU in scope — the command's own declared SKUs, plus whatever SKUs the named
    reservation's lines add, which a `scope` CTE computes from the third arm so the fourth can filter
    on it, all inside the one statement.

  A SKU or key a command does not have binds `null` for that arm's parameter rather than skipping the
  arm — `= null` matches no row, the same zero rows a skipped arm would have produced — because a
  prepared statement cannot have an optional arm. `JdbcLedger.assembleLeanSnapshot` reads the rows
  back into exactly the `Snapshot` `readSnapshot` would have built from four separate reads, including
  marking a SKU in scope that has no row `absent` rather than leaving it out.

- **`reclaimLimit > 0`, and the sweep,** are unchanged: the read-only repeatable-read transaction this
  ADR found too expensive for the common case, kept for the uncommon one, because their later
  statements depend on what an earlier one in the same load found.

A JSON-aggregating statement — one row, four `json_agg`/`row_to_json` columns instead of a `union all`
with null-padded columns — was the other candidate and is mentioned in "Alternatives" below; `union
all` won on the strength of staying inside `ResultSet` column access the rest of the adapter already
uses, with no JSON parsing dependency added for it.

Orderings are preserved exactly: the stock arm keeps `order by sku collate "C", shard`, and the lines
arm keeps the plain-collation order `SELECT_LINES` already used, because the differential test
compares PostgreSQL against the in-memory ledger row for row and a reordering would show up as a
disagreement that is not actually one.

*Later:* the statement no longer has a `scope` CTE, a window function or an `ORDER BY`, and returns its
rows in no order. The reason above did not hold for it; "Later: what the statement cost" below says what
it cost and what replaced it.

## Consequences

**A reserve's and a commit's lean load are one statement**, proved by watching the connection a test
wraps: `aLeanReserveLoadIsOneStatement` and `aLeanCommitLoadIsOneStatement` in `JdbcLedgerTest` assert
exactly one `prepareStatement` call and no `setAutoCommit`, `commit` or `rollback` call between them.
Before this change, the same two loads issued six and seven respectively (counting those calls) —
watched failing first, for that count, before `loadLean` existed.

**`aSnapshotIsOneInstant`'s old mechanism — interfering between an earlier read and a later one —
has no seam left on the lean path**, because there is only one read. It now exercises a reclaiming
load instead, which still opens the old transaction, unchanged. A new test,
`aSingleStatementSnapshotIsOneInstant`, proves the lean path's point a different way: a two-arm
statement whose first arm sleeps for two seconds and whose second reads a stock row a concurrent
transaction updates and commits during that sleep still returns the pre-update value, because
PostgreSQL gave both arms the snapshot taken when the statement started, not when each arm happened
to run. Splitting that same query into two separate statements — simulating the bug this is meant to
catch — made it read the post-update value, confirmed by hand while writing the test.

**The differential test and the concurrency test pass unchanged** — six seeds across shard
configurations, and four real-threads suites — which is the evidence that no decision changed, only
how a snapshot for one of them is read.

**Measured, and the result is a null one at this scale.** `ContentionBenchmark` with
`-Dbench.dbcpus=2 -Dbench.shards=16 -Dbench.seconds=30`, interleaved three runs a side against the
commit this branched from (`1c6b598`):

| | Checkouts/s (3 runs) | Mean | Conflict rate | Transactions/checkout |
| --- | --- | ---: | ---: | ---: |
| Before | 920.1, 1004.0, 866.6 | 930.2 | 6.5–6.9% | 4.3–4.4 |
| After | 997.1, 1014.5, 840.4 | 950.7 | 6.5–6.8% | 4.3 |

+2.2% on the mean, inside the ±25% run-to-run spread this laptop already shows (before: 14.8% of its
own mean; after: 18.3%) — not a measurable improvement here. `docker stats` during a run confirmed the
container was genuinely CPU-bound (173–194% of its 200% cap) while this stood, so the null result is
not a benchmark that failed to bind its own constraint. Two things make that unsurprising on reflection:
`ContentionBenchmark`'s 6.5–6.9% conflict rate means a meaningful share of its database time goes to
apply-side retries this change does not touch, which this change leaves exactly as expensive as
before — `pg_stat_statements` was not loaded on the container to break the total down further; and the
9.18 ms the load test measured for the removed `SET` came from a statement **queueing** for two shared
vCPUs on a remote database already saturated by 8,000 shoppers, which a four-statement, 64-caller
benchmark on localhost Postgres does not reproduce the queueing depth of, even with the same CPU cap.
The round-trip count is halved either way — the one-statement tests above prove that directly — and
`transactions/checkout` is unchanged for a different reason than either of those: PostgreSQL's
`xact_commit` counts one autocommit statement the same as a five-statement explicit transaction, so
this metric was never going to move for this change and should not be read as evidence either way.
The next AWS load test, against a real `db.t4g.micro` or its replacement, is the number that actually
answers whether this helps in production; this benchmark's job was only to confirm the change is
correct under contention, which it is.

## Later: what the statement cost

The load test that recorded the ledger database's load with Database Insights put this statement at
**30.6% of all the database's load**, the largest single item, with CPU 67% of the load overall.
`pg_stat_statements` on a quiet server had it at about 0.30 ms an execution, against 0.06 ms for a stock
update and 0.12 ms for a line insert, and a checkout runs it about 2.25 times. It returns sixteen to
nineteen rows for the loads below, and the plan a connection settles on after a few calls says where the
time went: a hash join of a sequential scan of `till_stock` against a hash-aggregated `scope`, a sort and
a window aggregate over each of the stock and the lines, and a sort of the lot by `kind`. The lookups of
the record, the reservation and its lines were the small part.

**What changed.** The window functions, the final `ORDER BY` and the `scope` CTE are gone. The stock arm
is `sku = any(? || array(select sku from target_lines))`, where `?` is the command's SKUs: one array of
those and the reservation's, and a row matches once however often the array names its SKU, so a SKU that
both name still comes back once, with every shard it has. The plan is one `Append` of three index lookups
and a scan of the stock, which is still all 512 rows of it. Nothing else moved: one statement, in
autocommit, and the same `Snapshot` out of it.

**The order was never needed.** The rows go into a `Snapshot` and a `Reservation`, whose constructors sort
what they are given — each SKU's shards by index, a reservation's lines by SKU, its allocations by SKU and
shard — and the SKUs of the snapshot follow the scope, which is the command's own order and then the
reservation's. The order the database returned the rows in could not reach a decision, and the
differential test, which compares the ledgers' final states and what each call answered, did not see it
either. It only cost the database.

**Measured**, with `SnapshotBenchmark`. It seeds what one run of the load test leaves — 32 SKUs of sixteen
shards, 100,000 reservations with 120,000 lines, 170,000 idempotency records, 135 MB with their indexes —
and runs a reserve's load (a hold that does not exist, one SKU) and a commit's (a hold that does) as one
prepared statement on one connection: ten rounds of 3,000 calls a statement, the statements in a different
order each round, after a warm-up, every page a call touched found in PostgreSQL's cache. PostgreSQL 17 in
the project's test image, capped at two CPUs, on a laptop with the client beside it. Per call, as it was
and as it is:

| | Reserve, before | Reserve, after | Commit, before | Commit, after |
| --- | ---: | ---: | ---: | ---: |
| CPU the backend spent (µs) | 130.8 | **97.3** (−26%) | 135.2 | **101.5** (−25%) |
| Executing, per `pg_stat_statements` (µs) | 65.0 | 55.3 (−15%) | 70.3 | 59.6 (−15%) |
| What the client waited, mean (p99) (µs) | 304 (448) | 230 (358) | 292 (437) | 228 (317) |
| Pages found in the cache | 14.0 | 14.0 | 16.2 | 16.2 |

This is the less favourable of the two runs that have every row in it. Over eight runs made while this was
written, the CPU per call fell by 25% to 37%, 31% at the median, and executing by 15% to 33%, 25% at the
median; with 2,500 stock updates a second running alongside, which leaves the table mostly dead versions of
its rows, the CPU fell by 35% and 32%. The CPU is the row that counts: executing leaves out starting and
ending the plan, which is where a plan of many nodes pays, and it is the CPU the database is short of. The
pages did not move, because the saving is the work done on the rows and the stock is still read in full.
The figures are a laptop's: the ledger's database, a burstable Graviton core, took about four times as
long executing the same statement (0.30 ms against 0.07), so the ratio carries over and the microseconds
do not. Nor is much more to be had from one statement: a call of `select 1` costs 17 µs of CPU here, a
lookup by primary key 30, and this statement's three lookups with no stock arm 56 to 60, of the 97 to 102 a
load costs now.

**What else was tried**, in the same run: CPU in microseconds a call, for a reserve's load and a commit's.

| Statement | Reserve | Commit |
| --- | ---: | ---: |
| As ADR 12 shipped it | 130.8 | 135.2 |
| Without the windows and the final sort, `scope` and its `UNION` kept | 110.6 | 112.5 |
| Stock arm `sku = any(?) or sku in (select sku from target_lines)` | 101.0 | 110.9 |
| **Stock arm `sku = any(? \|\| array(select sku from target_lines))`, shipped** | **97.3** | **101.5** |
| Stock read SKU by SKU in a `lateral` join (below) | 87.9 | 90.3 |

Dropping the sorting and the windows is most of the first saving, and replacing the hash join over a
deduplicated `scope` with an array is the rest. The last row is the cheapest statement measured, and it was
not taken. Its stock arm:

```sql
from (select distinct sku from unnest(? || array(select sku from target_lines)) as t(sku)) scope
cross join lateral (
  select sku, shard, on_hand, reserved, version from till_stock where till_stock.sku = scope.sku offset 0
) s
```

It reads the stock by index, as the stock should be read, and executes in 32 µs against 55. The CPU it
saves over the statement shipped is 10% in this run, and 3% to 19% in seventeen of the eighteen
comparisons across all the runs; in the eighteenth it cost 3% more. Against that:

- `offset 0` is a fence, which the next person to tidy the statement will take out. Without it the planner
  turns the join back into a hash join of a scan, which is the statement it replaces.
- Each SKU is read by a bitmap scan chosen on a margin of 9.6 against 11.4 for a sequential one, over a
  table of five pages, and which it picks moves with the table's statistics. On the empty tables of the
  test database the same statement plans differently, so the suite cannot hold it to reading by index
  without filling a table first.
- It is a tenth of a statement that is already a quarter to a third cheaper. It is the next thing to try if
  this is still the largest item at the next load test, and `-Dbench.statement` measures it.

**How many SKUs a command names** changes which statement is cheapest. The load test's orders name one SKU
(seven in ten) or two; a store order may name twenty. `sku = any(array)` compares each of the 512 rows with
each SKU of the array, so its cost grows with both, and the hash join over a deduplicated set of SKUs, which
costs more to start, does not. CPU of a reserve's load against the statement it replaced, in the same run,
twelve rounds of 4,000 calls:

| SKUs named | 1 | 2 | 5 | 10 | 20 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Shipped, `sku = any(? \|\| array(...))` | −36% | −29% | −21% | −14% | −12% |
| A hash semi join, `sku in (select unnest(? \|\| array(...)))` | −24% | −25% | −31% | −33% | −39% |
| The `lateral` read above | −40% | −37% | −36% | −30% | −28% |

The statement shipped beats the hash join for one SKU or two, which is what the load test makes, loses to it
from five, and is never dearer than the one it replaced at any size measured. A statement for each size is
possible, and was not taken for the sake of orders that are rare.

**A faster statement can be planned on every call.** PostgreSQL plans a prepared statement for the values
it is given the first five times, and afterwards keeps the plan that ignores them unless planning for the
values was cheaper by more than planning costs. The `lateral` read over `unnest(?::varchar[])` alone, which
ought to have been the best of all, has a far cheaper plan for one SKU than for the ten the general plan
assumes, so whether it settles depends on what its first five calls named: with one SKU each it stayed on
plans made for the values for twenty-five calls in twenty-five, and with twenty in them it settled on the
general plan at the sixth. Each connection of a ledger would decide for itself. Planned on every call it
cost 108 µs of planning a call and 259 µs of CPU, against 148 for the statement it was meant to replace,
while the executing column, which leaves planning out, made it look cheaper than the one shipped. The
statement shipped and the two other rows above concatenate their array with a subquery's, whose length the
planner cannot know in either kind of plan, so the two cost the same and each settles after five calls
whatever they named; that was checked, twenty SKUs first and one.

**Proving it.** Four tests in `JdbcLedgerTest`, each shown to fail for the break it names:

- `theLeanStatementSortsAndJoinsNothing`: the statement's generic plan has no sort, window, aggregate or
  join. Written first, it failed on the old statement for the `Sort` that plan opened with.
- `aLeanLoadSettlesOnOnePlan`: after twenty loads on one connection, PostgreSQL has run a generic plan
  more often than it planned the statement afresh. It fails for the `lateral` read over `unnest(?)`:
  sixteen fresh plans, none generic.
- `aLeanSnapshotIsTheTransactionalOne`: for ten shapes of command — a key used before and one not, a hold
  that exists and one that does not, SKUs the ledger has and one it has never heard of, a command that
  names SKUs its own hold holds, a SKU in four shards, a name that sorts first only in code point order —
  the lean snapshot equals the one the unchanged transactional load reads, down to the order of its SKUs,
  and hand-derived values fix what that is. It passed on the old statement, which it pins, and fails when
  the stock arm leaves out the reservation's SKUs.
- `aLeanSnapshotDoesNotDependOnRowOrder`: the same ten, with the rows of every query handed back
  reversed, rotated and shuffled with five seeds. It passed on the old statement too, and fails when the
  assembly takes the order of its SKUs from the rows.

The differential test, the concurrency test, the one-statement tests and
`aSingleStatementSnapshotIsOneInstant` pass unchanged.

**`ContentionBenchmark`**, to see whether the write path noticed and how much of the database this statement
is, now that [ADR 14](0014-one-round-trip-apply.md) has made a decision one statement and not seven:
`-Dbench.dbcpus=2 -Dbench.shards=16`, thirty seconds measured after five, five runs a side one after the
other, against `61cc0c1`, the commit this sits on:

| | Checkouts/s (5 runs) | Mean | Conflict rate | Statements/checkout |
| --- | --- | ---: | ---: | ---: |
| Before | 1,351.4, 1,361.1, 1,368.8, 1,334.0, 1,346.8 | 1,352.4 | 4.6–5.2% | 4.1 |
| After | 1,723.3, 1,708.7, 1,755.2, 1,797.7, 1,738.5 | 1,744.7 | 4.5–5.3% | 4.0–4.1 |

+29.0% on the mean, every pair faster, by 25% to 35%, and the slowest run after faster than the fastest
before. `docker stats` read 177% to 194% of the container's 200% cap in every sample of every run, so the
database's CPU is what limits this benchmark, and the statement is a larger share of that CPU now that the
writes cost so little: the same change measured against `6069d4b`, before ADR 14, gave 930 and 1,053 on the
mean of five pairs, +13%, ahead in each pair but by about the spread within a side. The checkout's p50 went
from 11.8–19.0 ms to 9.8–14.0 ms and its p99 from 215–417 ms to 176–320 ms. The conflict rate and the
statements a checkout sends did not move, which they should not: nothing about the writes changed. The
change before this one in ADR 12, which saved round trips, did not show here at all (+2.2%); this one saves
CPU, which is what this benchmark is short of.

## Alternatives

**A JSON-aggregating statement**: one row, `(select row_to_json(r) from (...) r) as record`, and
similarly for the reservation, its lines (`json_agg`) and the stock (`json_agg`). Equally one
statement and equally correct. Rejected for needing a JSON parser on the Java side for a value this
adapter otherwise never needs one for — every other read in `JdbcLedger` is `ResultSet` column
access — and for making the four reads' independent shapes less visible at the SQL call site than
four `union all` arms with a `kind` column do.

**Shrinking the reclaiming path to one statement too**: rejected for scope, not for difficulty. Its
later reads depend on what an earlier one in the same load finds, and it is rare — only a command
that would otherwise be short of stock reclaims, and shortfall is uncommon while stock exists — so the
four-statement transaction it already runs is the uncommon load's cost, not the common one's.

**Changing the connection's isolation level instead of a transaction's.** Asking, setting and
resetting it costs three statements, each its own transaction — the fourth load test's main
expense, and the reason the current transaction-scoped `SET TRANSACTION` exists at all
([`JdbcLedger`](../../till-jdbc/src/main/java/io/till/jdbc/JdbcLedger.java)). Worse than what this ADR
replaces, not better.

**Keeping the old mechanism for `aSnapshotIsOneInstant` on the lean path**, by having the test
interfere inside a statement rather than between two of them. Rejected: there is no JDBC-visible seam
inside one statement's `executeQuery` call to interfere at, short of rewriting the production SQL
text from the test to insert a test-only delay — which would mean the test was no longer proving
anything about the statement that ships. `aSingleStatementSnapshotIsOneInstant` proves the same
PostgreSQL guarantee with a statement shaped like the real one instead.
