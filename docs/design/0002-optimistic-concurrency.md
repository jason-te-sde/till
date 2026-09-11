# 2. Optimistic concurrency, not row locks

**Status:** accepted

## Context

Two callers reserving the same SKU must not both get the last unit. Something has to serialise them.

The traditional answer is `SELECT ... FOR UPDATE`: take a row lock, decide, write, commit. It is
simple, it is obviously correct, and every database supports it.

The case this project exists for is a flash sale — a thousand callers on one SKU in the same second.
A row lock turns that into a queue, and the queue's length is the latency of everybody in it. The
thousandth caller waits for nine hundred and ninety-nine transactions, each of which includes a
network round trip from the application. Worse, the lock is held across whatever the application does
between the read and the write, so a slow instance makes everybody wait.

## Decision

Every row carries a `version`. A decision is made against a snapshot and each mutation it produces
names the version it expects:

```sql
update till_stock set on_hand = ?, reserved = ?, version = version + 1
 where sku = ? and version = ?
```

If the row moved, the statement matches nothing, the transaction rolls back, and `apply` returns
`false`. The loop loads again and decides again.

Nothing is locked between the read and the write. A caller that thinks for a second blocks nobody.

There is **no backoff between attempts**. A conflict means somebody else's transaction committed,
which means progress was made; sleeping would add latency to a system that is making progress.
Backoff belongs in the caller that gives up, and `TillClient` has it.

`false` is not an error. A **check constraint violation** is, and is not retried: that means the
application tried to write a level the database knows is impossible, and retrying it would spin
around a real bug forever.

## Consequences

**Under contention, callers retry instead of queueing.** The measured shape of that: a 10,000-seed
simulation of eight contending callers produced 4.8 million conflicts across 30 million invariant
checks, and every one of them was resolved by loading again. 200 real threads against PostgreSQL
competing for 20 units produce exactly 20 sales.

**A caller can be refused.** After `till.max-attempts` the command fails with `ConflictException`,
which the server answers with 503 and `Retry-After`. That is a real difference from locking, where a
caller waits instead: here, extreme contention shows up as a retryable error rather than as unbounded
latency. Given a bounded connection pool that is the better failure — unbounded latency with every
thread parked on a lock is how one hot SKU takes down a service.

**Writes touch more rows than strictly necessary.** A decision that reclaims three expired holds
writes four rows, and each is a chance to conflict. `till.reclaim-limit` bounds it.

**Both numbers of a stock row move together.** `PutStock` writes absolute values rather than deltas,
which means applying one twice is harmless — and it means two decisions that touch a row always
conflict, even when their deltas would have commuted. That is a deliberate trade: commuting deltas
would allow more concurrency and would make it impossible to state what the row's value was derived
from.

## Alternatives

**`SELECT ... FOR UPDATE`.** Rejected above. Worth saying it is not *wrong* — for a service where one
SKU never has more than a handful of concurrent callers it is simpler and perfectly fine.

**`SELECT ... FOR UPDATE SKIP LOCKED`.** Solves a different problem (work queues), not this one.

**Serializable isolation.** PostgreSQL would detect the conflict for us and we could drop the version
column. It also turns every conflict into an exception that aborts the transaction, costs more to
track, and — the deciding point — makes the conflict something only the database can explain. With an
explicit version, the in-memory ledger can implement exactly the same semantics, which is what makes
the simulator and the differential test possible at all.

**One clever `UPDATE` per operation.** Fast, and it moves the rules into SQL. See ADR 1.
