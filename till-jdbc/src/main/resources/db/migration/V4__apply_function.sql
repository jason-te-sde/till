-- A decision written in one statement (docs/design/0014-one-round-trip-apply.md).
--
-- JdbcLedger.apply wrote a decision as a transaction of separate statements: BEGIN, a statement for
-- each row, COMMIT, each a round trip of its own, with every row already written locked across all
-- of them. This function takes the whole decision as arrays, and the one statement that calls it, in
-- autocommit, is the transaction: it begins, writes and commits inside one round trip.
--
-- It applies; it does not decide. Every value it writes is one the kernel computed, every version it
-- checks is one the kernel read, and it writes in the order the kernel lists them, which is the order
-- the transaction it replaces wrote them in: the stock rows first, in SKU and shard order, so that two
-- decisions never wait for each other in a cycle; then the state changes, the new reservations and
-- their lines, the events and the idempotency record. JdbcLedger refuses a decision listed in any
-- other order rather than have this write it in one nobody chose.
--
-- A row that is not at the version the decision expects, or an insert whose key is already taken,
-- is a refusal. The function raises SQLSTATE TL001, a class PostgreSQL does not use, which undoes
-- everything the statement has written, and JdbcLedger answers false. Every other error, a check
-- violation above all, is raised as PostgreSQL raised it, and reaches the caller as an exception.

create or replace function till_apply(
    put_sku            varchar[],
    put_shard          integer[],
    put_on_hand        bigint[],
    put_reserved       bigint[],
    put_version        bigint[],
    set_id             varchar[],
    set_state          varchar[],
    set_version        bigint[],
    insert_id          varchar[],
    insert_key         varchar[],
    insert_state       varchar[],
    insert_created_at  timestamptz[],
    insert_expires_at  timestamptz[],
    line_reservation   varchar[],
    line_sku           varchar[],
    line_shard         integer[],
    line_quantity      bigint[],
    event_key          varchar[],
    event_payload      text[],
    event_recorded_at  timestamptz[],
    record_key         varchar,
    record_fingerprint varchar,
    record_outcome     text,
    record_recorded_at timestamptz
) returns void
language plpgsql
as $$
begin
    -- A refusal has to mean that somebody else got there first. Arrays of one kind that differ in
    -- length are a malformed call, and would otherwise pass for one: a missing element reads as null
    -- and matches no row.
    if cardinality(put_shard) <> cardinality(put_sku) or cardinality(put_on_hand) <> cardinality(put_sku)
            or cardinality(put_reserved) <> cardinality(put_sku)
            or cardinality(put_version) <> cardinality(put_sku)
            or cardinality(set_state) <> cardinality(set_id) or cardinality(set_version) <> cardinality(set_id)
            or cardinality(insert_key) <> cardinality(insert_id)
            or cardinality(insert_state) <> cardinality(insert_id)
            or cardinality(insert_created_at) <> cardinality(insert_id)
            or cardinality(insert_expires_at) <> cardinality(insert_id)
            or cardinality(line_sku) <> cardinality(line_reservation)
            or cardinality(line_shard) <> cardinality(line_reservation)
            or cardinality(line_quantity) <> cardinality(line_reservation)
            or cardinality(event_payload) <> cardinality(event_key)
            or cardinality(event_recorded_at) <> cardinality(event_key) then
        raise exception 'till_apply was given arrays of one kind that differ in length'
            using errcode = '22023';
    end if;

    -- Mutation.PutStock. No version means the row must not exist yet.
    for i in 1 .. cardinality(put_sku) loop
        if put_version[i] is null then
            insert into till_stock (sku, shard, on_hand, reserved, version)
            values (put_sku[i], put_shard[i], put_on_hand[i], put_reserved[i], 0)
            on conflict (sku, shard) do nothing;
            if not found then
                raise exception 'stock % shard % already exists', put_sku[i], put_shard[i]
                    using errcode = 'TL001';
            end if;
        else
            update till_stock
               set on_hand = put_on_hand[i], reserved = put_reserved[i], version = version + 1,
                   updated_at = now()
             where sku = put_sku[i] and shard = put_shard[i] and version = put_version[i];
            if not found then
                raise exception 'stock % shard % is not at version %', put_sku[i], put_shard[i], put_version[i]
                    using errcode = 'TL001';
            end if;
        end if;
    end loop;

    -- Mutation.SetReservationState: expirations of the holds a decision reclaims, then its own.
    for i in 1 .. cardinality(set_id) loop
        update till_reservation set state = set_state[i], version = version + 1
         where id = set_id[i] and version = set_version[i];
        if not found then
            raise exception 'reservation % is not at version %', set_id[i], set_version[i]
                using errcode = 'TL001';
        end if;
    end loop;

    -- Mutation.InsertReservation: the reservations, then the lines of all of them, a line per shard.
    for i in 1 .. cardinality(insert_id) loop
        insert into till_reservation (id, idem_key, state, created_at, expires_at, version)
        values (insert_id[i], insert_key[i], insert_state[i], insert_created_at[i], insert_expires_at[i], 0)
        on conflict (id) do nothing;
        if not found then
            raise exception 'reservation % already exists', insert_id[i] using errcode = 'TL001';
        end if;
    end loop;
    if cardinality(line_reservation) > 0 then
        insert into till_reservation_line (reservation_id, sku, shard, quantity)
        select * from unnest(line_reservation, line_sku, line_shard, line_quantity);
    end if;

    -- The events, a row at a time, so that their sequence numbers ascend in the decision's order.
    for i in 1 .. cardinality(event_key) loop
        insert into till_outbox (dedupe_key, payload, recorded_at)
        values (event_key[i], event_payload[i], event_recorded_at[i])
        on conflict (dedupe_key) do nothing;
        if not found then
            raise exception 'event % already exists', event_key[i] using errcode = 'TL001';
        end if;
    end loop;

    -- The idempotency record, absent for a command without a key.
    if record_key is not null then
        insert into till_idempotency (idem_key, fingerprint, outcome, recorded_at)
        values (record_key, record_fingerprint, record_outcome, record_recorded_at)
        on conflict (idem_key) do nothing;
        if not found then
            raise exception 'idempotency key % already exists', record_key using errcode = 'TL001';
        end if;
    end if;
end
$$;
