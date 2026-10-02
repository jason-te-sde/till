-- A low fillfactor on till_stock (docs/design/0009-hot-sku-shards.md, "Later").
--
-- ADR 9 splits a hot SKU's stock into up to sixty-four shards, so that two commands on one SKU
-- conflict only when they land on the same shard. That divides the contention by row, not by page.
-- The load test's thirty-two games, split sixteen ways, are 512 rows, and at the default fillfactor
-- of 100 those pack onto five 8 KB pages (measured: about a hundred rows to a page). A page is what a
-- buffer pin guards, and what an update needs free space on to leave the indexes alone (a HOT
-- update), so 512 rows on five pages are still a handful of hotspots underneath the sharding.
--
-- fillfactor 10 puts about ten rows on a page instead: the same 512 rows on about fifty pages
-- (measured; see StockFillfactorTest). The range considered was 10 to 20, and the low end wins
-- because the table is bounded by games x shards rather than by traffic: it is a few hundred KB
-- anywhere in that range, so there is nothing to trade against fewer rows to a page.
--
-- Setting the parameter does not move a row that is already on disk, so on its own it would spread
-- only the rows written after this migration. `vacuum full` would move the rest, but it cannot run
-- inside a transaction block and Flyway runs this whole file in one. So the table is rebuilt here: a
-- copy under the new fillfactor, made from whatever till_stock holds when this runs, and then the
-- original is dropped and the copy takes its name, its constraints and its indexes. Every deployment
-- this repo has migrates an empty table and stocks it afterwards, so none of them takes the path with
-- rows in it; StockFillfactorTest runs this file against 512 rows that predate it.
create table till_stock_v4 with (fillfactor = 10) as
    select sku, on_hand, reserved, version, updated_at, shard from till_stock;

-- "create table as" keeps no constraints and no defaults, so they are put back. sku and shard are
-- not null by way of the primary key below. The not null on on_hand and reserved matters beyond
-- tidiness: a check constraint passes a null, so without them the oversell guard could be skipped.
alter table till_stock_v4 alter column on_hand set not null;
alter table till_stock_v4 alter column reserved set not null;
alter table till_stock_v4 alter column version set not null;
alter table till_stock_v4 alter column updated_at set not null;
alter table till_stock_v4 alter column updated_at set default now();
alter table till_stock_v4 alter column shard set default 0;

-- The original goes before the copy gets its constraints. The primary key's index is a relation, its
-- name is unique within the schema, and the original still holds till_stock_pkey. The copy then takes
-- the original's constraint names back, so nothing that refers to them (MigrationTest, an operator's
-- psql history) has to learn new ones.
drop table till_stock;

alter table till_stock_v4
    add constraint till_stock_possible check (reserved >= 0 and on_hand >= 0 and reserved <= on_hand);
alter table till_stock_v4 add constraint till_stock_shard check (shard >= 0);
alter table till_stock_v4 add constraint till_stock_pkey primary key (sku, shard);
alter table till_stock_v4 rename to till_stock;

-- The index V2 added went with the table; this is it again, as V2 wrote it.
create index till_stock_sku_c on till_stock (sku collate "C");
