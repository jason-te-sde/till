-- A hot SKU's stock in several rows (docs/design/0009-hot-sku-shards.md).
--
-- A SKU's stock row becomes one of its shards: every existing row is shard 0 of its SKU, which is
-- what a SKU that was never split is, and every existing hold took its units from shard 0. A SKU is
-- split by the ledger's shard command, never by editing these rows.
--
-- Both primary keys are rebuilt, which locks each table while it happens. The stock table has a row
-- per SKU and takes no time; a line table with years of holds in it is a maintenance window.

alter table till_stock add column shard integer not null default 0;
alter table till_stock drop constraint till_stock_pkey;
alter table till_stock add primary key (sku, shard);
alter table till_stock add constraint till_stock_shard check (shard >= 0);

-- A hold's line is now one row per shard it took units from: usually one, several only when no one
-- shard had them all.
alter table till_reservation_line add column shard integer not null default 0;
alter table till_reservation_line drop constraint till_reservation_line_pkey;
alter table till_reservation_line add primary key (reservation_id, sku, shard);
