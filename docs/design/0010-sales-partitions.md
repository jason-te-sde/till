# 10. Monthly partitions for the sales roll-up

**Status:** accepted

## Context

`store_sales_daily` (`(sku, day)`, `V6__orders_and_sales.sql`) is the roll-up `Projector` writes on
every `StockCommitted` event and `Games` reads for "best sellers this week" and for ranking related
games (`Games.SALES_JOIN`, `SALES_WINDOW_DAYS = 7`). Every read of it is windowed the same way:
`where day >= ?`, seven days. Nothing reads it any other way.

At thirty-two games the table is tiny — a handful of rows a day, nothing a sequential scan would
notice. The decision below is not about today's size. It is about what the same table costs after a
year of a bigger catalogue: a "last seven days" read still has to skip whatever history came before
it, and the only way to retire a closed month today is a `delete` sized to however many rows that
month has, leaving dead tuples for autovacuum to clean up afterwards. Partitioning by `day` turns the
read into "touch one or two partitions, ignore the rest" and turns retirement into dropping a table —
an operation that costs the same whether the partition held ten rows or ten million.

**Why this table and not the ledger's.** `till_idempotency`, `till_outbox` and `till_reservation`
(`till-core` / `till-jdbc`) each depend on a unique constraint for a correctness property: a retried
request finds its own record because the key is unique, and a redelivered event is deduplicated the
same way. PostgreSQL requires a unique or primary key constraint on a partitioned table to **include
the partition key**, so partitioning any of those three by time would force the key idempotency and
deduplication depend on to include a timestamp — and two requests at different instants, which must
collide, would stop colliding. That is a real weakening of a guarantee other components rely on,
bought for tables the retention sweeper already prunes in bounded batches
(`RetentionSweeper`, `till-server`) without needing a partition to do it. `store_sales_daily` has no
such constraint to protect: its primary key is `(sku, day)`, `day` is already in it, and nothing
about the roll-up's correctness depends on the key being anything other than what it already is.
This is the one table in the system partitioning is free for.

## Decision

Range-partition `store_sales_daily` by `day`, one partition per month, named
`store_sales_daily_y<year>m<month>` (e.g. `store_sales_daily_y2026m09`), plus a `default` partition
as the table's catch-all.

- **The migration converts in place** (`V7__sales_partitions.sql`): the old table is renamed aside, a
  partitioned table of the same shape takes its name, a partition is created for every month the
  existing data actually spans plus the current and next month, the rows are copied across, and the
  old table is dropped — one Flyway migration, one transaction: Postgres DDL is transactional and
  Flyway runs the whole file as one, so a failure partway rolls back to the unpartitioned table rather
  than leaving it half converted.
- **A `default` partition always exists.** PostgreSQL refuses an insert that matches no partition
  only when there is no default; with one, a row for a month nobody has created yet is kept, not
  rejected. That is what makes the next point safe rather than a race against every write.
- **`SalesPartitionMaintenance`** (`io.till.store.sales`) ensures this month's and next month's
  partitions exist, on startup and once a day — the same shape as `till-server`'s `RetentionSweeper` /
  `ExpirySweeper` / `OutboxPublisher`, the closest prior art in this codebase for "a thing that must
  keep happening without a release," adapted into `till-store`, which had none of its own yet. It also
  drops whole months older than `store.sales.retention` (default 13 months; the record refuses a
  value shorter than `Games.SALES_WINDOW_DAYS` at startup, because a retention shorter than the window
  it exists to keep cheap is a misconfiguration, not a valid small number).
- **A month created while `default` already holds rows for it is handled, not assumed away.**
  PostgreSQL refuses to create or attach a partition that would pull rows out from under `default`
  unless they are moved out first. `SalesPartitionMaintenance` checks for that case before creating a
  month's partition and, when it finds rows waiting there, moves them into a table built with the
  same constraints the ordinary path would have given it and attaches that, instead of either losing
  the rows or letting the create fail.
- **The primary key and the secondary index are declared once, on the parent.** PostgreSQL
  propagates both to every partition created the ordinary way (`create table ... partition of ...
  for values from (...) to (...)`); only the rescue-from-`default` path recreates them by hand, because
  it attaches a table that was never created as a partition in the first place.
- **The whole pass runs in one transaction, entered only after a claim.** Every store instance runs
  `SalesPartitionMaintenance` at the same moment on a fresh deploy (`ApplicationReadyEvent`), and
  `create table if not exists` is not race-free in PostgreSQL — two sessions can both pass the
  existence check and then collide creating the same relation. `pg_try_advisory_xact_lock`, the same
  pattern as `till-jdbc`'s `JdbcLedger` outbox-publishing claim (`CLAIM_PUBLISHING` /
  `PUBLISHING_LOCK`), means at most one instance ever runs this DDL at a time; the rest find the claim
  taken and do nothing that pass. The single transaction is what makes a crash mid-rescue safe: a task
  that dies after moving rows out of `default` but before attaching the table they went into used to
  leave that table behind, unattached, under the exact name the next pass's existence check matches —
  so it was never revisited, and the month's sales became invisible to every query, permanently and
  silently. One transaction means the connection dropping with the process rolls the whole pass back,
  `default` included, so the next pass starts from exactly where the last successful one left off.
  `set local lock_timeout` (`store.sales.lock-timeout`, default 2s), set once the claim is held,
  bounds how long the DDL waits for its lock on `store_sales_daily` — which PostgreSQL extends to
  every one of its partitions, `default` included. Without it, a slow query already holding that lock
  would queue every later reader and writer of the table behind this pass, for as long as the slow
  query runs, because PostgreSQL's lock queue is first in, first out.

## Consequences

**A seven-day read touches one or two partitions.** The window never spans more than one month
boundary, so it is never more than two. `EXPLAIN` on the query `Games` runs shows exactly that —
asserted on the partitions named in the plan, not on cost estimates, because those are what stay true
across a Postgres version (`SalesPartitioningTest`).

**Retiring a month is a `drop table`**, not a `delete` sized to however much history had accumulated
in it, and not a lock held for as long as that delete would take.

**One more thing has to keep running.** `SalesPartitionMaintenance` is one more scheduled job that
can fail silently if nobody is watching logs for it — the same cost every sweeper in this system
already carries, and no larger than that. A failed run leaves next month's partition missing for a
day, which `default` absorbs without losing anything.

**A lock the DDL cannot get within `lock_timeout` costs one pass, not a stalled table.** The pass
rolls back and tries again next interval; a partition is created a month ahead of when anything needs
it, so losing a day to contention costs nothing a caller would notice. The failure is logged at
`WARN`, distinct from any other failure, because it is the expected outcome of something else holding
`store_sales_daily` open for a while, not a bug in this job.

**`default` is still a sequential scan if rows keep landing there.** A clock far out of step, or the
job disabled for longer than a month, grows it the same way the old unpartitioned table grew. Normal
operation keeps it empty, because every month gets its own partition before any row needs it.

## Alternatives

**Each statement autocommitting on its own, no claim.** The first version of this design. It missed
two things, both found in review before this shipped: concurrent `create table if not exists` on a
fresh deploy — every instance runs the first pass at the same moment — can collide in PostgreSQL
rather than one of them simply finding the table already there; and without one transaction around
the rescue sequence, a task that died between moving rows out of `default` and attaching the table
they went into left that table behind, unattached, invisible to every later pass's existence check,
and so invisible to every query, forever. Both are why the pass is now a single claimed transaction
rather than a sequence of independent statements.

**Leave it unpartitioned and `delete` old rows on a schedule**, the way `RetentionSweeper` already
prunes the ledger's history. Simpler — no migration, no new component — but a `delete` of a month's
rows costs proportionally to that month's size and does not shrink what a seven-day read skips over on
its way past the months still waiting their turn to be deleted. This is the status quo, replaced here
because it stops improving as the catalogue and its history grow, which the ledger's three tables
either do not do (idempotency, outbox) or are explicitly kept forever by default (reservations).

**Partition by `sku`** instead of, or alongside, `day`. Every read of this table is time-windowed and
none of them is sku-windowed — `bestSellers` and `related` both scan every sku in range — so this
would add partitions without pruning anything a real query asks for.

**Partition the ledger's tables too, for symmetry.** Rejected in Context above: their unique
constraints are the correctness property, and a timestamp is not a column any of them can afford to
add to one.

**A fixed set of partitions created once, sized generously (five years, say), and no maintenance
component.** Fewer moving parts, but the retention window becomes a choice made once at migration
time instead of a setting, and the partitions for the distant future sit empty for years before
anything proves they were the right size. This project already has the shape for "the next one of
these must exist before it is needed" in `ExpirySweeper`'s sibling problem; reusing it costs less
than inventing a one-shot alternative.
