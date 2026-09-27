-- Orders, and the sales they add up to.

-- An order is the store's record of a purchase; the reservation is the ledger's record of the stock.
-- They are linked one to one, and the order never decides anything the reservation decides: whether
-- the units exist, whether the hold has run out, whether it was already paid for. It records what the
-- customer was charged and what they were told.
create table store_order (
    id             uuid         primary key,
    -- The OIDC subject, not an email address. A subject never changes and is never reassigned; an
    -- email can do both, and an order history keyed by one follows the address rather than the person.
    customer       varchar(255) not null,
    -- The customer's own key for this checkout attempt, so that a retried "Place order" returns the
    -- order it already placed instead of placing another.
    idem_key       varchar(128) not null,
    reservation_id varchar(64)  not null unique,
    status         varchar(16)  not null check (status in ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED')),
    total_cents    bigint       not null check (total_cents >= 0),
    currency       char(3)      not null,
    created_at     timestamptz  not null,
    -- The hold's deadline, copied so the order page can count down without asking the ledger.
    expires_at     timestamptz  not null,
    -- When it left PENDING, for whichever reason the status says.
    closed_at      timestamptz,
    unique (customer, idem_key),
    -- A closed order has a closing time and an open one does not. Cheap to state, and it is the kind of
    -- half-applied update that is otherwise found months later in a report that does not add up.
    constraint store_order_closed_iff_not_pending
        check ((status = 'PENDING') = (closed_at is null))
);

-- "My orders", newest first, with the id breaking ties within a microsecond.
create index store_order_by_customer on store_order (customer, created_at desc, id);

-- What was bought, at the price it was bought at.
--
-- The title and price are copied rather than joined, and there is deliberately no foreign key to the
-- catalogue: an order is a record of what happened, and it has to read the same after the game is
-- renamed, repriced or delisted. Joining would silently rewrite history every time marketing edited
-- a row.
create table store_order_line (
    order_id         uuid         not null references store_order (id) on delete cascade,
    sku              varchar(64)  not null,
    title            varchar(200) not null,
    unit_price_cents bigint       not null check (unit_price_cents >= 0),
    -- Ten per game per order. A limit every real store has, for the same reason: without one, a single
    -- script can hold a release's entire stock for fifteen minutes and nobody else can buy it.
    quantity         int          not null check (quantity between 1 and 10),
    primary key (order_id, sku)
);

-- Units sold per game per day, maintained from the ledger's commit events.
--
-- A roll-up rather than a scan of orders, so that "best sellers this week" reads seven rows per game
-- instead of every order line ever written. Counted from the event stream rather than from this
-- store's own orders, because a sale made through any other channel the ledger serves is still a sale.
create table store_sales_daily (
    sku   varchar(64) not null,
    day   date        not null,
    units bigint      not null check (units >= 0),
    primary key (sku, day)
);

create index store_sales_daily_by_day on store_sales_daily (day, sku);
