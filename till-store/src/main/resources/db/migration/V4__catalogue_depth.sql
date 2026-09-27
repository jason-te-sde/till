-- A catalogue deep enough to shop in.
--
-- V1 had enough to prove the event path: a title, a price, a blurb. A store needs what a customer
-- actually filters and reads by — a sale price beside the list price, tags, a description, a way to
-- search — and the indexes that keep each of those from being a sequential scan once the catalogue
-- stops fitting on one screen.

alter table store_game
    add column list_price_cents bigint,
    add column tags text[] not null default '{}',
    add column description text not null default '',
    add column features text[] not null default '{}',
    -- Null for most games. The home page's hero is a curated list, and a curated list is a column
    -- somebody sets, not a query that happens to put the right things first.
    add column featured_rank int unique;

-- Every existing game is at its list price until somebody says otherwise.
update store_game set list_price_cents = price_cents;

alter table store_game
    alter column list_price_cents set not null,
    -- A "sale" that costs more than the list price is a data error, and one a storefront would
    -- otherwise render as a negative discount.
    add constraint store_game_sale_not_above_list check (price_cents <= list_price_cents);

-- Search. A generated column, so the vector can never disagree with the text it came from, and
-- weighted so that a match in the title outranks a match three sentences into the description.
--
-- The text search configuration is named explicitly. `to_tsvector(text)` with the server's default
-- configuration is only STABLE — it depends on a setting — and PostgreSQL refuses a STABLE
-- expression in a generated column. `to_tsvector('english', text)` is IMMUTABLE.
--
-- Tags are deliberately not in the vector: array_to_string is STABLE for the same reason, and tags
-- are a filter rather than something to rank on. They have an index of their own below.
alter table store_game add column search tsvector generated always as (
    setweight(to_tsvector('english', title), 'A')
        || setweight(to_tsvector('english', studio), 'B')
        || setweight(to_tsvector('english', genre), 'B')
        || setweight(to_tsvector('english', blurb), 'C')
        || setweight(to_tsvector('english', description), 'D')
) stored;

create index store_game_search on store_game using gin (search);

-- `tags @> array['co-op']` — containment, which is what "has this tag" means and what a GIN index
-- on an array serves. A btree on an array column serves equality of the whole array, which nobody
-- ever asks for.
create index store_game_tags on store_game using gin (tags);

create index store_game_genre on store_game (genre);
create index store_game_released on store_game (released_on desc);
