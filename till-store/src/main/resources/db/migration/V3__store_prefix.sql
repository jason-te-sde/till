-- The service outgrew its name, and the tables follow it.
--
-- `till-catalogue` became `till-store` when it took on orders, sign-in and the operator console's
-- backend. The catalogue is still one of the things it owns; it is no longer the only one, and a
-- schema where the order tables say `store_` and the tables beside them say `catalogue_` would be a
-- schema that had to be explained.
--
-- A rename rather than an edit to V1: V1 has been applied wherever this has run, and Flyway's whole
-- value is that an applied migration never changes. `alter table ... rename` touches the catalogue,
-- not the rows, so it is instant whatever the tables hold.
alter table catalogue_game rename to store_game;
alter table catalogue_availability rename to store_availability;
alter table catalogue_consumed_event rename to store_consumed_event;
alter index catalogue_consumed_event_by_time rename to store_consumed_event_by_time;
