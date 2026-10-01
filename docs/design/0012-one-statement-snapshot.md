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
