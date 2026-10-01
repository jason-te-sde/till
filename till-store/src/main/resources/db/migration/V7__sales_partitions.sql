-- Converts store_sales_daily into a table range-partitioned by month, so a seven-day best-seller
-- read touches one or two partitions instead of the whole table, and a retired month is a dropped
-- table instead of a delete sized to however many rows it held. docs/design/0010-sales-partitions.md
-- has the reasoning, including why this table and not the ledger's.
--
-- Postgres cannot turn an existing plain table into a partitioned one in place, so this moves the
-- current table aside, builds the partitioned replacement under the old name, copies the rows across,
-- and drops the original. One transaction: Postgres DDL is transactional and Flyway runs this whole
-- file as one, so a failure partway rolls back to the unpartitioned table rather than half-converting
-- it.

alter table store_sales_daily rename to store_sales_daily_unpartitioned;
drop index store_sales_daily_by_day;

-- The primary key must include the partition key on a partitioned table; (sku, day) already does,
-- which is the whole reason this table can be partitioned for free while the ledger's cannot be
-- (see the design note). Shape otherwise unchanged from V6.
create table store_sales_daily (
    sku   varchar(64) not null,
    day   date        not null,
    units bigint      not null check (units >= 0),
    primary key (sku, day)
) partition by range (day);

-- Declared once, on the parent: Postgres propagates both the index and the primary key above to
-- every partition created the ordinary way, including ones SalesPartitionMaintenance creates later.
create index store_sales_daily_by_day on store_sales_daily (day, sku);

-- The safety net. Without a default, an insert for a month nobody has created a partition for yet
-- is refused outright; with one, it is kept, and SalesPartitionMaintenance moves it into a proper
-- partition once that month's partition exists.
create table store_sales_daily_default partition of store_sales_daily default;

-- One partition per month, from the oldest existing row's month (if any) through at least next
-- month, so every row already in the table has a home before it is copied, and the service has a
-- month's head start before SalesPartitionMaintenance has to create another.
do $$
declare
    this_month  date := date_trunc('month', current_date)::date;
    first_month date;
    last_month  date;
    month_start date;
    partition_name text;
begin
    select least(coalesce(date_trunc('month', min(day))::date, this_month), this_month),
           greatest(coalesce(date_trunc('month', max(day))::date, this_month), (this_month + interval '1 month')::date)
      into first_month, last_month
      from store_sales_daily_unpartitioned;

    month_start := first_month;
    while month_start <= last_month loop
        partition_name := format('store_sales_daily_y%sm%s', to_char(month_start, 'YYYY'), to_char(month_start, 'MM'));
        execute format(
            'create table if not exists %I partition of store_sales_daily for values from (%L) to (%L)',
            partition_name, month_start, (month_start + interval '1 month')::date);
        month_start := (month_start + interval '1 month')::date;
    end loop;
end $$;

insert into store_sales_daily (sku, day, units)
    select sku, day, units from store_sales_daily_unpartitioned;

drop table store_sales_daily_unpartitioned;
