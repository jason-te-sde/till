# 9. A hot SKU's stock in several rows

**Status:** accepted

## Context

Every command on a SKU writes that SKU's stock row, and under optimistic concurrency
([ADR 2](0002-optimistic-concurrency.md)) two commands that read the same version of a row cannot
both apply: one of them loads again and decides again. The row is the unit of contention, and a SKU
had exactly one.

The load test measured what that costs. In its fourth run 53% of the ledger's stock updates found the
row had moved since it was read, with 8,000 shoppers spread over thirty-two games
([load test](../load-test.md#30-september-expired-holds-written-off-when-they-are-needed)). The
contention benchmark reproduces it on a laptop: 64 callers checking out on 32 SKUs, and 53% of the
decisions they make conflict. A flash sale — everyone on one SKU — is the same thing with nowhere to
spread.

## Decision

A SKU's stock can be kept in several rows, **shards**, each owning a slice of the SKU's on-hand. Two
commands on the same SKU conflict only when they touch the same shard.

- **One SKU, several rows.** `till_stock` is keyed by SKU and shard. Every SKU has shard 0; a SKU split
  `n` ways has shards `0` to `n - 1`. The SKU's level — what the API, the events and the inspectors
  report — is the sum of its shards, and each shard on its own is a possible level: never more
  reserved than on hand.
- **A hold takes its units from one shard when it can.** The reservation id picks where to start, by
  hash, so holds spread evenly and a retry of one request starts where it did. A line takes its
  units from the first shard, in order from there, that has them all; a line no single shard can
  cover takes them from several, in the same order.
- **A hold is refused only for the SKU's whole stock.** A snapshot reads every shard of a SKU in scope,
  so "not enough" still means not enough across all of them. Sharding changes which rows are written,
  never the answer.
- **A hold remembers where its units came from.** Each of its lines is stored with the shard it took
  from, one row per shard it touched; commit, release and expiry return the units there.
- **Splitting is a command.** `Command.Shard` splits a SKU at least `n` ways — an operator's decision
  about a SKU they know will be hot, before the sale — and spreads its unreserved on-hand evenly over
  the shards. A SKU already split that many ways is left as it is. Shards are only ever added.
- **Deliveries even the shards out.** A positive adjustment goes to the shards with the least
  available first; a write-off comes from the ones with the most. Holds drain shards unevenly, and
  the next delivery levels them again.

## Consequences

**Contention divides.** With `n` shards and holds spread by id, two concurrent commands on one SKU
touch the same row about one time in `n`. The contention benchmark (`ContentionBenchmark` in
`till-jdbc`: PostgreSQL limited to two CPUs, 64 callers checking out on 32 SKUs through 32
connections, 30 s, each configuration run twice):

| Shards per SKU | Decisions that conflicted | Callers that gave up | Checkouts a second | p99 | Transactions per checkout |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 53.8–54.5% | 383–439 | 370–381 | 517–519 ms | 8.9–9.0 |
| 8 | 12.9–13.2% | 2 | 395–606 | 322–572 ms | 4.6–4.7 |
| 16 | 6.9–7.1% | 0 | 659–673 | 283–295 ms | 4.3 |

The conflict rate and the transactions are the numbers to trust; throughput on a laptop moves by a
quarter between identical runs. One more run at sixteen shards is left out of the table: it managed
19 checkouts a second, with the laptop's load average at 67, and its conflicts (8.2%) were the others'. A checkout is two commands and each is two transactions, so four is
the floor: at sixteen shards almost nothing is thrown away.

**A hold that no shard can cover alone touches several rows**, and so do an adjustment and a split.
Near sell-out, when shards have run dry unevenly, holds split and the contention comes back — when
stock is scarce, and most holds are refused for want of it anyway.

**Shards only grow.** Taking one away would mean moving the holds on it, a write to every one of them.

**The reported version is the sum of the shards' versions.** It still moves on every write, which is
all anything outside the ledger uses it for.

**The events do not change.** A hold's events carry its lines per SKU, as before; which shard a unit
came from is the ledger's business, and the store's projection never needs to know.

## Alternatives

**A row lock** (`SELECT ... FOR UPDATE`). No conflicts and no work thrown away, but every command on a
SKU waits for the one before it, across a round trip to the application. ADR 2 rejected it for
exactly the case this is about.

**Conditional updates** (`reserved = reserved + ? where on_hand - reserved >= ?`). Holds on one row
would commute and never conflict. But the database would decide the level rather than the kernel,
and the snapshot–decide–apply seam of [ADR 1](0001-pure-kernel.md) would have an exception at its
busiest point — and the in-memory ledger, the simulator and the differential test would all have to
learn it.

**Splitting every SKU, always.** A snapshot reads every shard of a SKU in scope, so a SKU nobody
contends for would pay for shards it does not need. Splitting is a decision about a SKU.

**A shard per caller or per instance.** Stable, and it would leave a hold's shard unrelated to where
the stock is. The reservation id is enough, and it is already in the command.

## Later

**A low fillfactor, to divide the page too.** Shards divide the row: with sixteen of them 7% of
decisions conflicted where 54% did, measured above. They do not divide the page underneath. 512
rows — 32 games × 16 shards — pack onto five 8 KB pages at the default fillfactor of 100, about a
hundred to a page, so whichever shards share a page share its buffer pin, and an update has little
free space on its own page to be a HOT update, one that leaves the indexes alone.
`V4__stock_fillfactor.sql` sets the table's fillfactor to 10: about ten rows to a page, the same 512
rows on about fifty pages. Ten rather than twenty because the table is bounded by games × shards
rather than by traffic — a few hundred KB anywhere in that range — so there is nothing to trade
against fewer rows to a page.

Setting the parameter does not move a row already on disk, and `VACUUM FULL`, which would, cannot run
inside the transaction Flyway runs a migration in. So the migration rebuilds the table: a copy under
the new fillfactor, made from whatever `till_stock` holds when it runs, then the original dropped and
the copy renamed into its place with the same constraints and the same index. Every deployment here
migrates an empty table and stocks it afterwards, so none of them takes that path with rows in it;
`StockFillfactorTest` runs the migration against 512 rows that predate it, and that is the only
coverage the path has.

Measured as above — `ContentionBenchmark`, PostgreSQL limited to two CPUs, 64 callers on 32 SKUs
through 32 connections, sixteen shards, 30 s measured after a 5 s warm-up — on `c98eea6` without this
migration and with it, alternating, the build without it first in each pair, five pairs:

| Pair | Checkouts/s, without | Checkouts/s, with | Conflicts, without | Conflicts, with | Transactions per checkout |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 970.9 | 891.9 | 6.5% | 6.6% | 4.3 / 4.3 |
| 2 | 1,010.1 | 904.4 | 6.8% | 6.6% | 4.3 / 4.3 |
| 3 | 999.8 | 977.8 | 6.3% | 6.5% | 4.3 / 4.3 |
| 4 | 1,032.9 | 1,009.6 | 6.8% | 6.6% | 4.4 / 4.3 |
| 5 | 1,010.8 | 1,018.0 | 6.5% | 6.8% | 4.3 / 4.3 |
| **Mean** | **1,004.9** | **960.3** | **6.58%** | **6.62%** | **4.3** |
| **Spread, max − min** | 62.0 (6.2%) | 126.1 (13.1%) | | | |

A null result. The means are 4.4% apart, and the five runs with the migration span nearly three
times that. The gap inside a pair went 79, 106, 22, 23 and −7 in the order the pairs ran, which does
not look like a steady cost of the migration, but five pairs cannot say more than that. Two runs of
the build without it, back to back, gave 984.8 and 965.0, 2% apart where the first two pairs were 8%
and 10% apart, so running second is not by itself the explanation. What does explain it was not
found. The one-minute load average, read five times around the last two pairs, ran from 3.6 to 12.6
on eight CPUs — a figure that includes this benchmark's own two-CPU database and its 64 callers —
and was not read during the first three; Consequences above already says that throughput on a laptop
moves by a quarter between identical runs. The conflict rate (6.58% and 6.62%, against 6.9–7.1%
above at the same sixteen shards) and the transactions per checkout (4.3 on both sides) are what
fillfactor has no mechanism to change, and they did not change.

This benchmark does not show whether updates became HOT updates — `n_tup_hot_upd` was not read — or
whether a page was ever the resource the callers waited on. The load test's database, which serves only
the platform's own traffic, is where that would show if it is real.
