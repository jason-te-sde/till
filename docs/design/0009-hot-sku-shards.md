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
