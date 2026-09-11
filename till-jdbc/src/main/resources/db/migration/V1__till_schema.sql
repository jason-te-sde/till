-- till: the whole schema.
--
-- Four tables and one rule each. Nothing here is generated from the Java types: the constraints are
-- written out so that a database administrator reading this file can see what the application
-- promises, and so that the database refuses a promise the application breaks.

create table till_stock (
    sku        varchar(64) primary key,
    on_hand    bigint      not null,
    reserved   bigint      not null,
    version    bigint      not null,
    updated_at timestamptz not null default now(),

    -- The oversell guard, at the level that cannot be bypassed by a bug in the application, by a
    -- migration script, or by somebody fixing data by hand at three in the morning. Every path that
    -- lowers on_hand or raises reserved is checked here as well as in the kernel.
    constraint till_stock_possible check (reserved >= 0 and on_hand >= 0 and reserved <= on_hand)
);

create table till_reservation (
    id         varchar(64)  primary key,
    idem_key   varchar(128) not null,
    state      varchar(16)  not null,
    created_at timestamptz  not null,
    expires_at timestamptz  not null,
    version    bigint       not null,

    constraint till_reservation_state check (state in ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    constraint till_reservation_order check (expires_at >= created_at)
);

-- Lines are a table rather than a column because the sweeper and the reclaim path both need to ask
-- "which expired holds are standing in the way of these SKUs", and that question has no index on a
-- delimited string.
create table till_reservation_line (
    reservation_id varchar(64) not null references till_reservation (id) on delete cascade,
    sku            varchar(64) not null,
    quantity       bigint      not null,

    primary key (reservation_id, sku),
    constraint till_reservation_line_quantity check (quantity > 0)
);

create index till_reservation_line_sku on till_reservation_line (sku);

-- Partial, because every query against it is about held holds past their deadline and nothing else
-- ever looks at expires_at. On a table where most rows are finished, this index is a small fraction
-- of the size of the unconditional one.
create index till_reservation_expiring on till_reservation (expires_at) where state = 'HELD';

-- For the administrative listing, which asks for the most recent holds. Newest-first and bounded is
-- the whole of that query: an operator wants the last hundred, and a listing that could return a
-- million rows is a listing that will one day be asked to.
create index till_reservation_recent on till_reservation (created_at desc, id);

-- The unique constraint on the primary key is the concurrency control for duplicate requests: two
-- copies of one command arriving at two servers both try to insert here, one wins, and the loser
-- reloads and replays what the winner recorded.
create table till_idempotency (
    idem_key    varchar(128) primary key,
    fingerprint char(64)     not null,
    outcome     text         not null,
    recorded_at timestamptz  not null
);

-- Old records are safe to delete once no client could still be retrying; see docs/operations.md.
create index till_idempotency_recorded_at on till_idempotency (recorded_at);

create table till_outbox (
    sequence     bigserial    primary key,
    dedupe_key   varchar(200) not null unique,
    payload      text         not null,
    recorded_at  timestamptz  not null,
    published_at timestamptz
);

-- Partial again: the publisher only ever asks for the unpublished tail, and once a row is published
-- it should stop costing anything to skip.
create index till_outbox_unpublished on till_outbox (sequence) where published_at is null;
