# 1. The rules are a pure function

**Status:** accepted

## Context

The rules for reserving stock are about ten decisions, and every one of them is about state that
lives in a database: what is on hand, what is already held, whether this key has been seen, whether
this hold has run out of time.

The obvious place to write them is where that state is. A service method opens a transaction, reads
what it needs, decides, writes, commits. That is how this is normally done and it works.

It is also untestable at the scale that matters. The failures in this problem are all about two
callers, a crash at the wrong moment, and a clock crossing a deadline — and each of those needs a
different interleaving to reproduce. Testing them through a transaction means a database round trip
per step, a scheduler nobody controls, and a failure that appears once in a thousand runs and is
gone when you look for it.

## Decision

`Kernel.decide(Snapshot, Command, Instant)` is a static function with no fields. It reads no clock,
opens no connection, and starts no thread. Time arrives as an argument. Rows arrive in a `Snapshot`
that somebody else assembled. It returns a `Decision` describing what should be written, and writes
nothing.

Identifiers are supplied by the caller for the same reason: generating one would mean reaching for a
random source, and a run would stop being reproducible.

## Consequences

**What this buys.** The simulator in `till-testkit` runs eight contending callers with injected
crashes, lost answers and clock jumps at about eighty thousand steps a second, checks every invariant
after every step, and reproduces any failure exactly from one integer. Ten thousand seeds is six
minutes and thirty million invariant checks. None of that is reachable through a database.

It also made the rules smaller. A function that cannot fetch anything cannot decide to fetch
something, so every input a decision depends on is visible in its signature.

**What it costs.** Somebody has to load the snapshot, and getting that wrong is a new class of bug.
`IncompleteSnapshotException` exists for it and is deliberately loud: a missing row raises rather
than looking like a SKU that is out of stock. The `Ledger` contract is correspondingly longer than a
service method would have been, and two of its subtleties — that the read must be one consistent
instant, and that a SKU with no row must still appear in the map — are the kind of thing that needs
writing down rather than inferring.

The other cost is a retry loop. A decision made against a snapshot can be stale by the time it is
applied, so `Till` loads, decides, and starts again if a version moved. That loop is four lines and
it is the price of not holding a lock across the decision.

## Alternatives

**Rules in a service method, inside the transaction.** Rejected: see above. This is the version that
cannot be simulated.

**Rules in SQL.** A single clever `UPDATE ... WHERE available >= ?` handles the simplest case and
nothing else: it cannot express a multi-line all-or-nothing hold, it cannot make the deadline the
truth rather than the stored state, and it cannot be tested without a database. It also puts the
rules somewhere no reader of the Java expects to find them.

**Rules in a stateful in-memory object that owns the data.** This is what a consensus library does,
and it is right there — but it requires the process to be the authority, which means one writer,
which means leader election and a replication story for a problem that a single PostgreSQL row solves.
