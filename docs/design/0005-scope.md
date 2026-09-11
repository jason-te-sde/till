# 5. What till does not do

**Status:** accepted

## Context

The temptation with a reservation service is to grow it into an inventory system, and then into a
warehouse system. This note is the list of things that were considered and left out, with the reason
for each, so that "why can't it…" has an answer that is not silence.

## Not implemented, on purpose

**A product catalogue.** till never joins to one. A SKU is an opaque string, at most 64 characters,
and the service holds no opinion about what it means — a database id, a barcode, a seat number, a
concert ticket tier. A caller that has a catalogue does not have to mirror it here, and the one thing
till would gain from knowing about products is validation it cannot do better than the caller.

**Multi-tenancy.** No tenant column, no per-tenant tokens. A caller that needs it runs an instance
per tenant or puts the tenant in the SKU. Adding it later means a migration and a decision about what
a shared SKU means, and getting that decision wrong is worse than not having the feature.

**Partial fulfilment.** A multi-line hold takes all of it or none of it, and the refusal reports how
far short every line fell so the caller can decide what to offer instead. "Take what you can" is a
business rule about substitution and back-order preferences, and it belongs where those live.

**Backorders and waitlists.** A refusal is final. Queueing a caller for stock that has not arrived
means owning a notification path and a fairness policy, which is a different service.

**Reserving a specific unit.** A hold is for a quantity, not for serial number 4417. Ticketing and
seat selection need identity; that is a different data model (one row per seat) and this one would be
the wrong starting point.

**Scheduled availability.** No "20 units available from Tuesday". A caller that needs it models each
window as its own SKU.

**Pricing, carts, orders, payment.** till is the piece those things call. It is deliberately the
narrowest part of a checkout, because the narrowest part is the one that can be made provably correct.

**Read replicas.** `GET /v1/stock/{sku}` reads the primary. Routing it to a replica would be easy and
would make `available` a number that is sometimes wrong in a direction that matters, and there is no
mechanism here to say how wrong. A caller that wants a cheap approximate number should cache the one
it gets.

**Rate limiting.** Belongs at the edge, where the identity of the caller is known and where a decision
can be made without a database round trip.

**Encryption at rest, beyond whatever the disk does.** See [`SECURITY.md`](../../SECURITY.md).

**A second database.** The adapter is PostgreSQL-specific — `on conflict do nothing`, `= any(?)`,
`collate "C"`, partial indexes. A MySQL adapter is perfectly possible and would implement the same
`Ledger` port and be checked by the same simulator; nobody has needed one. The port exists so that
this stays a decision somebody else can make.

## Deliberately minimal rather than absent

**Authentication is two static bearer tokens.** No OAuth, no mTLS, no key rotation without a restart.
Two static tokens do not need a filter chain and an authentication manager, and the surface of a
framework that provides those needs a better reason than "it is what people use". A deployment that
needs more puts till behind something that does more.

**The event publisher writes log lines.** Kafka, SNS, a webhook: all of them are "take this batch and
tell me it arrived", which is a four-line interface, and none of them needs a change to the ledger.
Shipping one of them would mean shipping its client library and its version conflicts to everybody.

**The client's JSON is hand-written.** About two hundred lines, strict, and thoroughly tested,
including what it refuses. The alternative is putting a serialisation library — and its upgrade
treadmill — into the dependency of somebody else's service. That trade would be wrong for a server
and is right for a client.

## Rejected as the wrong shape

**A reservation as an event-sourced aggregate.** Every operation appends and state is a fold. It
would make the audit log primary rather than derived, which is attractive, and it would make
`available` a query over a log — which is either slow or a projection that can drift, and "the
projection drifted" is exactly the failure this project is about.

**Reservations in Redis.** Fast, and it is what most flash-sale implementations do. It also means the
durability of a promise depends on a replication mode, and an oversell after a failover is a
customer-facing error nobody can explain. PostgreSQL is fast enough for a handful of rows per
command, and it can say "this is committed" and mean it.
