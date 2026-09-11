# 3. A key is answered once, and a rejection is an answer

**Status:** accepted

## Context

A client sends `POST /v1/reservations`, the connection drops, and it never learns whether the hold
was taken. It has to retry. If the retry takes a second hold, the customer's stock is held twice and
one of those holds will expire unused — or worse, both get committed and the customer is charged
twice.

This is not an edge case. It is what happens every time a mobile network hiccups.

## Decision

Every mutating request carries an `Idempotency-Key`. The outcome of the first execution is stored
under that key, and a later command with the same key returns the stored outcome and **writes
nothing at all** — no mutations, no events, not even the expiry of a hold the snapshot showed was
stale.

Three details are where the difficulty actually lives.

**The unique constraint is the concurrency control.** Two copies of one request arriving at two
servers at the same instant both load a snapshot with no record, both decide to act, and both try to
insert. One transaction wins; the other is refused by the primary key, returns `false`, reloads,
finds the record, and replays it. No lock, and no window in which both succeed.

**A reserve's fingerprint excludes the reservation id.** The server mints an id per HTTP request, as
a stateless server must. Including it would give every retry a different fingerprint, and every retry
would be rejected as a key reuse. Lines are canonicalised first, so a body listing the same SKUs in a
different order is the same request.

**A key used for a different request is refused, not served.** The two requests cannot both be the
one the key names, and guessing which is meant is how a retry quietly becomes a second order. That
refusal is the one outcome never recorded under its own key: recording it would make the mistake
permanent and refuse the client's real request forever.

**Rejections are recorded.** A retry of a request that was refused for want of stock is told the same
"no". This is the choice most likely to surprise, so: the alternative is that a client which retried
a timed-out request is told "out of stock" the first time and "created" the second, having been
charged for one order and shipped two. An idempotency key is a name for *an attempt*, and the answer
to an attempt does not change because the world did.

## Consequences

A client that wants a fresh answer uses a fresh key. That is the right interface — it makes "is this
the same attempt?" the client's decision, which is the only place it can be decided — but it has to
be said out loud, because "retry with the same key and get a different answer once stock arrives" is
what people expect.

`tillctl` generates a key per invocation, which is right for a person at a terminal and wrong for a
script. `--key` is how a script says what it means, and the help text says so.

The outcome has to be stored in a form that reproduces exactly. `Codec` writes a one-line text form
rather than JSON, so the module has no serialisation dependency, and instants are ISO-8601 rather
than epoch milliseconds so they round-trip without losing precision. `Till` truncates its clock
readings to microseconds for the same reason: PostgreSQL stores microseconds, and an instant that
loses precision on the way to disk comes back as a different value than the one the caller was told.

The table grows forever and is not pruned automatically. Deleting a record makes a retry of that
command execute again, so the cutoff has to be longer than the longest client retry window, which is
a fact about your clients rather than about till. `docs/operations.md` has the statement.

## Alternatives

**Deduplicate on a natural key**, like an order id. Works when there is one, and till has no
catalogue and no orders. A caller with an order id can put it in the key.

**Store a hash of the request and compare, without storing the outcome.** Detects the retry but has
nothing to answer it with, so the client gets a 409 for a request that succeeded.

**Time-boxed deduplication only** — remember keys for five minutes and forget them. Smaller table, and
the failure mode is a retry after six minutes executing twice. Rejected because the window is a
guess about somebody else's client.

**Idempotency at the gateway.** Caching the response for a repeated key is the same idea one layer
out, and it cannot work here: the gateway would have to cache across instances, know which requests
are safe to cache, and be part of the same transaction as the write. The last one is impossible.
