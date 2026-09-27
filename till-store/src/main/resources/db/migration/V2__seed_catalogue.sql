-- A catalogue to demonstrate against.
--
-- Invented titles rather than real ones. A public portfolio repository that ships a table of other
-- people's trademarks is a small, avoidable problem, and nothing here is better demonstrated by a
-- name somebody else owns.
--
-- Seeded in a migration rather than by the application at startup, so that two instances starting at
-- once cannot both insert it, and so that "what is in the catalogue" is versioned like everything
-- else. A real deployment would replace this file with an import from whatever owns its products.
--
-- No availability rows: those arrive from till's events. A game with no row yet reads as sold out,
-- which is the correct thing for a storefront that has not heard anything about it.

insert into catalogue_game (sku, title, studio, genre, price_cents, released_on, cover, blurb) values
  ('sunless-orbit',    'Sunless Orbit',        'Halfmoon Interactive', 'Survival',   5999, '2026-03-12', 'orbit',
   'Keep a dying station lit. Every system you repair takes power from one you will need later.'),
  ('paper-cartography','Paper Cartography',    'Ink & Compass',        'Puzzle',     2499, '2025-11-04', 'map',
   'Fold a map to make two distant places touch. Two hundred folds, one rule, no hints.'),
  ('deep-field',       'Deep Field',           'Nine Volt Games',      'Strategy',   4499, '2026-01-28', 'field',
   'Command a telescope array across forty years. Discoveries arrive long after you asked for them.'),
  ('rust-and-rain',    'Rust and Rain',        'Cold Harbour Studio',  'Action RPG', 6999, '2026-06-19', 'rust',
   'A city that floods on a schedule you can learn. Everything you leave behind stays where it fell.'),
  ('tessera',          'Tessera',              'Halfmoon Interactive', 'Puzzle',     1999, '2025-08-22', 'tessera',
   'Tile a floor that changes shape as you tile it. Finishing is not always possible, and it tells you.'),
  ('night-shift-audio','Night Shift Audio',    'Fourth Wall Works',    'Narrative',  3499, '2026-02-14', 'audio',
   'Master eight hours of tape from a radio station that closed in 1981. Somebody is still calling in.'),
  ('lantern-run',      'Lantern Run',          'Two Rivers Collective','Platformer', 1499, '2026-04-30', 'lantern',
   'Carry a light up a mountain. It goes out when you land badly, and the mountain is dark.'),
  ('ledger-of-tides',  'Ledger of Tides',      'Cold Harbour Studio',  'Simulation', 5499, '2026-05-07', 'tides',
   'Run a harbour for a century. The books must balance; the sea has not read them.');
