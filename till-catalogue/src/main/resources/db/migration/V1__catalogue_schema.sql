-- The storefront's own schema, in its own database.
--
-- Nothing here is the ledger. till owns how much stock exists and who is holding it; this service
-- owns what a game is called, what it costs, and a *copy* of availability that is only ever as
-- current as the last event it consumed. Keeping those in one schema would make it possible to join
-- them, and the first such join is the moment the storefront starts deciding whether a sale is
-- allowed — which is the mistake this whole project exists to avoid.

create table catalogue_game (
    sku          varchar(64)  primary key,
    title        varchar(200) not null,
    studio       varchar(120) not null,
    genre        varchar(60)  not null,
    price_cents  bigint       not null check (price_cents >= 0),
    released_on  date         not null,
    cover        varchar(16)  not null,
    blurb        varchar(400) not null
);

-- The projection. One row per SKU, maintained by consuming till's events.
--
-- `available` is stored rather than computed as on_hand - reserved, because that subtraction is
-- till's rule and not this service's to reimplement. If the two ever disagree, the ledger is right;
-- this table is a cache with a timestamp on it.
create table catalogue_availability (
    sku        varchar(64) primary key,
    on_hand    bigint      not null,
    reserved   bigint      not null,
    available  bigint      not null,
    updated_at timestamptz not null
);

-- The inbox, and the reason the projection can survive at-least-once delivery.
--
-- Reservation events carry *deltas* — a list of lines — so applying one twice moves the numbers
-- twice. Kafka will deliver the same record twice whenever a publisher dies between a successful
-- send and the write that records it, which is not an edge case but the documented contract. So
-- every event is inserted here by its deduplication key in the same transaction as the projection
-- update: the second copy hits the primary key, the transaction rolls back, and the numbers are
-- untouched.
--
-- This is the mirror of till's own idempotency table. An outbox on the producer without an inbox on
-- the consumer is half a design.
create table catalogue_consumed_event (
    dedupe_key  varchar(200) primary key,
    sequence    bigint       not null,
    consumed_at timestamptz  not null
);

-- Consumed events are only needed for as long as a redelivery is possible, which in practice is
-- bounded by the topic's retention. Pruning walks the oldest first.
create index catalogue_consumed_event_by_time on catalogue_consumed_event (consumed_at);
