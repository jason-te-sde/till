# 6. Events go through the table, at least once

**Status:** accepted

## Context

Something downstream needs to know when stock moves: a search index, an analytics pipeline, a
warehouse. The obvious implementation is to publish after committing:

```java
transaction.commit();
broker.publish(event);      // <-- here
```

The window between those two lines is a process crash away from a change nobody downstream will ever
hear about. Publishing before the commit is worse: the event describes a change that may roll back.
Putting the broker call inside the transaction does not help either — a broker is not part of the
database's atomicity, and now a broker outage is a failed checkout.

## Decision

Events are rows. `Ledger.apply` writes the mutations, the events and the idempotency record **in one
transaction**, so an event cannot exist without the change it describes and vice versa. A separate
publisher reads the unpublished tail, delivers it, and marks it afterwards.

Marking afterwards is the whole of the delivery guarantee. A crash between the send and the mark
repeats the send, so delivery is **at least once** and never at most once. Doing it the other way
round would lose an event every time the process died at the wrong moment, and nothing downstream
would know which one.

Every event therefore carries a `dedupeKey()` that is a function of what happened rather than of when
it was published: `reserved:<reservation-id>`, `committed:<reservation-id>`,
`adjusted:<idempotency-key>`. Each of those can happen at most once, so the key is unique by
construction, stable across republishing, and stable across the same row being read by two publisher
instances.

Several instances may publish at once, and no attempt is made to stop them. Coordinating would buy
exactly-once at the cost of a lock on a path that does not need one, and the consumer has to be
idempotent regardless.

## Consequences

**The backlog is the metric that matters.** `till_outbox_backlog` growing means everything downstream
is working from a picture of stock that is falling further behind. It is the first thing in the alert
table in `docs/operations.md`.

**Consumers must deduplicate.** That is a real requirement placed on somebody else, and the
deduplication keys are documented and stable so that it is possible. A consumer that does not will
occasionally see a duplicate.

**The table grows and is not pruned automatically.** How much history to keep is a replay decision.

**Ordering is per reservation, not global.** Sequences ascend, and all of one reservation's events are
written by decisions that conflict with each other, so they cannot be concurrent. Two different
reservations have no ordering guarantee between them, and asking for one would mean a single writer.

**The invariants check the outbox against the state.** `onHand` must equal the adjustments in the
outbox minus the commits, and `reserved` the reservations minus the commits, releases and expiries.
That is what catches a change written without its event — the `PARTIAL_APPLY` flaw in
`FlawDetectionTest` is exactly this mistake, and it is caught at step 1.

## Alternatives

**Publish after commit.** Rejected above. It is the common implementation and the reason "the index
is missing an item" is a familiar complaint.

**Logical decoding / change data capture.** Read PostgreSQL's WAL and turn row changes into events.
Genuinely good, and it removes the table and the publisher entirely. It also makes the event schema a
projection of the table schema, so a column rename is a breaking change for every consumer, and it
needs a replication slot — which, left behind by a consumer that stopped, fills the disk. Worth doing
for a large deployment; too much operational surface to make it the default.

**Two-phase commit between the database and the broker.** Requires an XA-capable broker and a
transaction manager, and gives distributed transactions to solve a problem a table solves.

**No events at all.** Let consumers poll `GET /v1/stock/{sku}`. Fine for a handful of SKUs, hopeless
for a catalogue, and it cannot express "this reservation was committed" at all.
