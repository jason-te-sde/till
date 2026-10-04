-- A batch of commands written in one statement (docs/design/0016-batched-commands.md).
--
-- till_apply is V5's, able to write what a batch of commands decided as one transaction: each stock
-- row once, at the version the batch's decisions would have left it at one at a time; a reservation
-- created and finished in the same batch inserted as it was left; and an idempotency record for every
-- command. Nothing it does is a decision. Every value and every version is one Till computed, and a
-- row not at the version the batch read, or a key already taken, is still a refusal, TL001, that
-- takes everything the statement wrote with it.
--
-- What is new, every argument of it with a default:
--
--   put_new_version   the version each stock row is written at. A row three decisions of a batch
--                     wrote is written once, and moved by three, as one at a time would have moved it.
--                     Null, as every caller before this one sends it, means one past the version
--                     expected, and 0 for a row the call creates.
--   insert_version    the version each new reservation is written at: 1 for one created and committed
--                     in the same batch. Null means 0.
--   records_*         an idempotency record for each command of the batch, in order.
--
-- The scalar record_* arguments are V5's and now default to null, so that a batch need not send them.
-- V5's function is dropped, so that there is one till_apply, and every argument after the arrays V4
-- had has a default: the calls of V5 and of V4, still sent by the last version's instances during a
-- rolling deploy, and again after a rollback, reach this function and are answered as they were.

drop function if exists till_apply(
    varchar[], integer[], bigint[], bigint[], bigint[],
    varchar[], varchar[], bigint[],
    varchar[], varchar[], varchar[], timestamptz[], timestamptz[],
    varchar[], varchar[], integer[], bigint[],
    varchar[], text[], timestamptz[],
    varchar, varchar, text, timestamptz,
    boolean);

create or replace function till_apply(
    put_sku             varchar[],
    put_shard           integer[],
    put_on_hand         bigint[],
    put_reserved        bigint[],
    put_version         bigint[],
    set_id              varchar[],
    set_state           varchar[],
    set_version         bigint[],
    insert_id           varchar[],
    insert_key          varchar[],
    insert_state        varchar[],
    insert_created_at   timestamptz[],
    insert_expires_at   timestamptz[],
    line_reservation    varchar[],
    line_sku            varchar[],
    line_shard          integer[],
    line_quantity       bigint[],
    event_key           varchar[],
    event_payload       text[],
    event_recorded_at   timestamptz[],
    record_key          varchar     default null,
    record_fingerprint  varchar     default null,
    record_outcome      text        default null,
    record_recorded_at  timestamptz default null,
    durable             boolean     default true,
    put_new_version     bigint[]    default null,
    insert_version      bigint[]    default null,
    records_key         varchar[]   default '{}',
    records_fingerprint varchar[]   default '{}',
    records_outcome     text[]      default '{}',
    records_recorded_at timestamptz[] default '{}'
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
            or (put_new_version is not null and cardinality(put_new_version) <> cardinality(put_sku))
            or cardinality(set_state) <> cardinality(set_id) or cardinality(set_version) <> cardinality(set_id)
            or cardinality(insert_key) <> cardinality(insert_id)
            or cardinality(insert_state) <> cardinality(insert_id)
            or cardinality(insert_created_at) <> cardinality(insert_id)
            or cardinality(insert_expires_at) <> cardinality(insert_id)
            or (insert_version is not null and cardinality(insert_version) <> cardinality(insert_id))
            or cardinality(line_sku) <> cardinality(line_reservation)
            or cardinality(line_shard) <> cardinality(line_reservation)
            or cardinality(line_quantity) <> cardinality(line_reservation)
            or cardinality(event_payload) <> cardinality(event_key)
            or cardinality(event_recorded_at) <> cardinality(event_key)
            or cardinality(records_fingerprint) <> cardinality(records_key)
            or cardinality(records_outcome) <> cardinality(records_key)
            or cardinality(records_recorded_at) <> cardinality(records_key) then
        raise exception 'till_apply was given arrays of one kind that differ in length'
            using errcode = '22023';
    end if;

    -- A version that did not move forwards would let a decision made against the old row match the new
    -- one, which is the one thing a version exists to prevent. Checked before anything is written.
    if put_new_version is not null then
        for i in 1 .. cardinality(put_sku) loop
            if put_new_version[i] is null or put_new_version[i] <= coalesce(put_version[i], -1) then
                raise exception 'stock % shard % would be written at version %, which is not past %',
                    put_sku[i], put_shard[i], put_new_version[i], coalesce(put_version[i], -1)
                    using errcode = '22023';
            end if;
        end loop;
    end if;
    if insert_version is not null then
        for i in 1 .. cardinality(insert_id) loop
            if insert_version[i] is null or insert_version[i] < 0 then
                raise exception 'reservation % would be written at version %', insert_id[i], insert_version[i]
                    using errcode = '22023';
            end if;
        end loop;
    end if;

    -- For this transaction only: the next one on this connection commits as it did before.
    if not durable then
        set local synchronous_commit to off;
    end if;

    -- The stock rows, in the order given: SKU and shard order, the order every caller locks them in.
    -- No expected version means the row must not exist yet.
    for i in 1 .. cardinality(put_sku) loop
        if put_version[i] is null then
            insert into till_stock (sku, shard, on_hand, reserved, version)
            values (put_sku[i], put_shard[i], put_on_hand[i], put_reserved[i], coalesce(put_new_version[i], 0))
            on conflict (sku, shard) do nothing;
            if not found then
                raise exception 'stock % shard % already exists', put_sku[i], put_shard[i]
                    using errcode = 'TL001';
            end if;
        else
            update till_stock
               set on_hand = put_on_hand[i], reserved = put_reserved[i],
                   version = coalesce(put_new_version[i], version + 1), updated_at = now()
             where sku = put_sku[i] and shard = put_shard[i] and version = put_version[i];
            if not found then
                raise exception 'stock % shard % is not at version %', put_sku[i], put_shard[i], put_version[i]
                    using errcode = 'TL001';
            end if;
        end if;
    end loop;

    -- State changes of the reservations the decisions read: expirations of reclaimed holds, and the
    -- commands' own.
    for i in 1 .. cardinality(set_id) loop
        update till_reservation set state = set_state[i], version = version + 1
         where id = set_id[i] and version = set_version[i];
        if not found then
            raise exception 'reservation % is not at version %', set_id[i], set_version[i]
                using errcode = 'TL001';
        end if;
    end loop;

    -- New reservations, each in the state the decisions left it in, then the lines of all of them, a
    -- line per shard.
    for i in 1 .. cardinality(insert_id) loop
        insert into till_reservation (id, idem_key, state, created_at, expires_at, version)
        values (insert_id[i], insert_key[i], insert_state[i], insert_created_at[i], insert_expires_at[i],
                coalesce(insert_version[i], 0))
        on conflict (id) do nothing;
        if not found then
            raise exception 'reservation % already exists', insert_id[i] using errcode = 'TL001';
        end if;
    end loop;
    if cardinality(line_reservation) > 0 then
        insert into till_reservation_line (reservation_id, sku, shard, quantity)
        select * from unnest(line_reservation, line_sku, line_shard, line_quantity);
    end if;

    -- The events, a row at a time, so that their sequence numbers ascend in the decisions' order.
    for i in 1 .. cardinality(event_key) loop
        insert into till_outbox (dedupe_key, payload, recorded_at)
        values (event_key[i], event_payload[i], event_recorded_at[i])
        on conflict (dedupe_key) do nothing;
        if not found then
            raise exception 'event % already exists', event_key[i] using errcode = 'TL001';
        end if;
    end loop;

    -- The idempotency records: V5's one, from a caller that sends it, and the batch's.
    if record_key is not null then
        insert into till_idempotency (idem_key, fingerprint, outcome, recorded_at)
        values (record_key, record_fingerprint, record_outcome, record_recorded_at)
        on conflict (idem_key) do nothing;
        if not found then
            raise exception 'idempotency key % already exists', record_key using errcode = 'TL001';
        end if;
    end if;
    for i in 1 .. cardinality(records_key) loop
        insert into till_idempotency (idem_key, fingerprint, outcome, recorded_at)
        values (records_key[i], records_fingerprint[i], records_outcome[i], records_recorded_at[i])
        on conflict (idem_key) do nothing;
        if not found then
            raise exception 'idempotency key % already exists', records_key[i] using errcode = 'TL001';
        end if;
    end loop;
end
$$;
