-- Indexes for the two administrative listings, which were both sequential scans.
--
-- Measured on 200,000 rows, PostgreSQL 17. Each `explain (analyze)` below is the plan before and
-- after; the numbers are why these exist rather than a guess about what might help.

-- `where sku collate "C" > ? order by sku collate "C"` could not use the primary key index, because
-- that index is built with the database's default collation and the query asks for another one. The
-- explicit collation is not optional — it is what makes this adapter and the in-memory one order
-- rows identically, which the differential test relies on — so the index has to match it.
--
--   before:  Gather Merge -> Sort (Sort Key: sku COLLATE "C")   -- 100,000 rows sorted for 100
--   after:   Index Scan using till_stock_sku_c                  -- 100 rows read
create index till_stock_sku_c on till_stock (sku collate "C");

-- `where state = coalesce(?, state) order by created_at desc, id` used till_reservation_recent,
-- which carries no state, so filtering to a rare state read the whole index and threw most of it
-- away. The console has a button per state, and the rarest is the one an operator clicks.
--
--   before:  Gather Merge -> Sort                          -- every row scanned, none matched
--   after:   Index Only Scan using till_reservation_by_state
--
-- till_reservation_recent stays: it is what the unfiltered listing uses, and this one cannot serve
-- that because its leading column is not in the query.
create index till_reservation_by_state on till_reservation (state, created_at desc, id);
