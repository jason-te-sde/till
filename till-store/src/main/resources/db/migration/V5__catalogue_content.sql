-- The catalogue a customer actually browses.
--
-- Thirty-two games across eight genres, four each, with the description, tags and sale prices a
-- store page needs. Every title, studio and word of copy is invented: a public repository shipping a
-- table of other people's trademarks is a small, avoidable problem, and nothing here is better
-- demonstrated by a name somebody else owns.
--
-- The eight that V2 seeded are updated rather than re-inserted, so a database that already ran V2
-- keeps its rows — and everything that references them — and simply gains the new columns' content.
--
-- Stock is deliberately absent. How many copies exist is the ledger's business; this is only what a
-- game is called and what it costs.

-- The original eight.
update store_game set
    title = 'Sunless Orbit', studio = 'Halfmoon Interactive', genre = 'Survival',
    price_cents = 4499, list_price_cents = 5999, released_on = '2026-03-12',
    cover = 'orbit', blurb = 'Keep a dying station lit. Every system you repair takes power from one you will need later.',
    description = 'The relay station at the edge of the system has lost its star. You have a reactor that cannot run everything, a crew that remembers when it could, and a supply ship eleven days out. Every decision is a trade: warmth or air, lights or comms, sleep or repairs. The station remembers what you chose.',
    features = array['Power routing where every system competes for the same watts', 'A crew with needs, moods and long memories', 'Eleven-day runs that end the same way only if you let them']::text[],
    tags = array['single-player', 'sci-fi', 'crafting', 'atmospheric', 'challenging']::text[],
    featured_rank = 1
where sku = 'sunless-orbit';

update store_game set
    title = 'Paper Cartography', studio = 'Ink & Compass', genre = 'Puzzle',
    price_cents = 2499, list_price_cents = 2499, released_on = '2025-11-04',
    cover = 'map', blurb = 'Fold a map to make two distant places touch. Two hundred folds, one rule, no hints.',
    description = 'An atlas that folds. Crease the page and the valley meets the coast; fold again and the road you need runs through the sea. There is one rule and it never changes — only the maps get stranger. No timers, no hints, no failure state: just a desk lamp and a problem.',
    features = array['Two hundred hand-drawn maps across nine regions', 'One rule, introduced once and never explained again', 'Unlimited undo, and no penalty for using it']::text[],
    tags = array['single-player', 'relaxing', 'controller']::text[],
    featured_rank = null
where sku = 'paper-cartography';

update store_game set
    title = 'Deep Field', studio = 'Nine Volt Games', genre = 'Strategy',
    price_cents = 4499, list_price_cents = 4499, released_on = '2026-01-28',
    cover = 'field', blurb = 'Command a telescope array across forty years. Discoveries arrive long after you asked for them.',
    description = 'Fund an observatory, point it at the dark, and wait. Deep Field is a strategy game about patience: grants run for years, signals take decades to confirm, and your rivals publish first if you blink. Plan an array that outlives the people who built it.',
    features = array['A forty-year campaign played across a single evening', 'Research that pays off long after you commit to it', 'Rival observatories racing you to every discovery']::text[],
    tags = array['single-player', 'turn-based', 'sci-fi', 'relaxing']::text[],
    featured_rank = 3
where sku = 'deep-field';

update store_game set
    title = 'Rust and Rain', studio = 'Cold Harbour Studio', genre = 'Action RPG',
    price_cents = 5249, list_price_cents = 6999, released_on = '2026-06-19',
    cover = 'rust', blurb = 'A city that floods on a schedule you can learn. Everything you leave behind stays where it fell.',
    description = 'The canal city of Verrin floods at dusk, every dusk, and the water takes whatever is not nailed down. Learn the tides, chart the dry roads, and fight your way through districts that change twice a day. Nothing respawns: every body, blade and dropped key stays exactly where it fell.',
    features = array['A hand-built city that floods on a learnable schedule', 'A persistent world — nothing you drop or defeat respawns', 'Weighty, stamina-driven combat across forty weapon families']::text[],
    tags = array['single-player', 'open-world', 'fantasy', 'challenging', 'controller']::text[],
    featured_rank = 2
where sku = 'rust-and-rain';

update store_game set
    title = 'Tessera', studio = 'Halfmoon Interactive', genre = 'Puzzle',
    price_cents = 1999, list_price_cents = 1999, released_on = '2025-08-22',
    cover = 'tessera', blurb = 'Tile a floor that changes shape as you tile it. Finishing is not always possible, and it tells you.',
    description = 'Each room is a floor to tile, and every tile you lay reshapes the room around it. Some rooms can be finished; some cannot, and Tessera tells you the moment that becomes true. The skill is noticing before it does.',
    features = array['Rooms that react to every tile you place', 'An honest game: it says when a puzzle has become unsolvable', 'A daily room shared by every player']::text[],
    tags = array['single-player', 'relaxing', 'controller']::text[],
    featured_rank = null
where sku = 'tessera';

update store_game set
    title = 'Night Shift Audio', studio = 'Fourth Wall Works', genre = 'Narrative',
    price_cents = 3499, list_price_cents = 3499, released_on = '2026-02-14',
    cover = 'audio', blurb = 'Master eight hours of tape from a radio station that closed in 1981. Somebody is still calling in.',
    description = 'You have been hired to digitise the archive of WKRL, a late-night station that went off the air in 1981. Scrub the tapes, clean the hiss, log the callers. One of them keeps calling back — on tapes recorded after the station closed.',
    features = array['Eight hours of fully voiced archival radio', 'A real audio desk: EQ, noise gates and splicing', 'A mystery told entirely through what was recorded']::text[],
    tags = array['single-player', 'story-rich', 'atmospheric']::text[],
    featured_rank = null
where sku = 'night-shift-audio';

update store_game set
    title = 'Lantern Run', studio = 'Two Rivers Collective', genre = 'Platformer',
    price_cents = 1499, list_price_cents = 1499, released_on = '2026-04-30',
    cover = 'lantern', blurb = 'Carry a light up a mountain. It goes out when you land badly, and the mountain is dark.',
    description = 'A mountain, a lantern and a long way up. Every hard landing dims the flame, every fall puts it out, and the path only exists where the light reaches. Precise, tense and quietly beautiful.',
    features = array['A single mountain, climbed in one continuous ascent', 'Light as your health bar, your map and your timer', 'A speedrun mode with ghost replays']::text[],
    tags = array['single-player', 'pixel-art', 'challenging', 'controller']::text[],
    featured_rank = null
where sku = 'lantern-run';

update store_game set
    title = 'Ledger of Tides', studio = 'Cold Harbour Studio', genre = 'Simulation',
    price_cents = 5499, list_price_cents = 5499, released_on = '2026-05-07',
    cover = 'tides', blurb = 'Run a harbour for a century. The books must balance; the sea has not read them.',
    description = 'Build a harbour town over a hundred years of trade, storms and silting channels. Every ship that docks is an entry in the ledger, every storm is a write-off, and the sea keeps its own accounts. A management sim for people who like their spreadsheets with weather.',
    features = array['A hundred-year campaign across four trading eras', 'Double-entry economics where every good is accounted for', 'Storms, tides and silt that reshape the harbour over decades']::text[],
    tags = array['single-player', 'real-time', 'relaxing']::text[],
    featured_rank = null
where sku = 'ledger-of-tides';

-- And twenty-four more.
insert into store_game
    (sku, title, studio, genre, price_cents, list_price_cents, released_on, cover, blurb, description, features, tags, featured_rank)
values
  ('ashen-crown', 'Ashen Crown', 'Meridian Forge', 'Action RPG', 5999, 5999, '2025-10-09', 'crown',
   'Inherit a burned kingdom and the grudges that burned it. Every duke you pardon remembers.',
   'The capital is ash and you are its heir. Ride out to hold what is left: duel pretenders, broker marriages, and decide which of your father''s enemies deserve mercy. Ashen Crown remembers every pardon and every execution, and so does every court you visit afterwards.',
   array['Sword-and-shield combat with a parry you can build a playstyle around', 'A court where mercy and cruelty both have long tails', 'Six regions, each ruled by a duke with an agenda of their own']::text[],
   array['single-player', 'fantasy', 'open-world', 'story-rich', 'controller']::text[],
   null),
  ('hollow-meridian', 'Hollow Meridian', 'Meridian Forge', 'Action RPG', 2799, 3999, '2024-11-21', 'orbit',
   'A ring-shaped world with the sun at its centre and something hollow at its heart.',
   'Hollow Meridian is set on the inside of a ring around a small, dimming sun. Climb the spokes, cross the rim, and find out why the ring was built and who is still maintaining it. Co-op for up to three players, with every region scaled to the party.',
   array['Drop-in co-op for up to three players', 'Traversal across the inside of a rotating ring world', 'Gear that is found, never bought']::text[],
   array['co-op', 'sci-fi', 'exploration', 'controller']::text[],
   null),
  ('saltbound', 'Saltbound', 'Lowtide Games', 'Action RPG', 2999, 2999, '2026-07-24', 'tides',
   'A drowned archipelago, a leaking boat and a harpoon. Dive for relics; surface before the salt takes you.',
   'The islands sank a generation ago and their treasures went with them. Dive the ruins, fight what nests there, and haul relics back to a floating market that pays well and asks nothing. Your boat leaks, your air runs out, and the deep is not empty.',
   array['Seamless diving between surface islands and sunken cities', 'A boat you patch, upgrade and occasionally lose', 'Harpoon combat built around distance and timing']::text[],
   array['single-player', 'exploration', 'fantasy', 'atmospheric']::text[],
   null),
  ('iron-chancellery', 'Iron Chancellery', 'Sable & Stone', 'Strategy', 4999, 4999, '2025-09-18', 'crown',
   'Govern an empire from a single desk. Every letter you sign moves an army.',
   'You never see a battlefield. You see the dispatches: reports, requests, rumours and demands, all arriving at the Chancellor''s desk faster than you can answer them. Iron Chancellery is grand strategy told through paperwork, where the most dangerous weapon is a signature.',
   array['Grand strategy played entirely through documents', 'Advisors who lie, and ledgers that catch them', 'A hundred-year campaign with no fixed victory condition']::text[],
   array['single-player', 'turn-based', 'story-rich']::text[],
   null),
  ('signal-and-siege', 'Signal & Siege', 'Nine Volt Games', 'Strategy', 3499, 3499, '2026-08-06', 'field',
   'Real-time tactics where your orders travel at the speed of a runner. Plan for what they will find.',
   'Command a fortress under siege, but your orders are carried by runners, flags and signal fires — and they arrive late, or not at all. Signal & Siege is a real-time tactics game about acting on old information and trusting the officers you cannot reach.',
   array['Orders that take time to arrive and can be intercepted', 'Officers with initiative who act when your signals fail', 'Online matches for two to four commanders']::text[],
   array['multiplayer', 'real-time', 'challenging']::text[],
   null),
  ('quiet-frontier', 'Quiet Frontier', 'Brightwater Labs', 'Strategy', 1749, 2499, '2025-03-27', 'map',
   'Settle a valley without a single battle. The land is the opponent, and it is patient.',
   'A peaceful 4X about a settlement growing into a valley over three hundred years. There are no armies to fight: only floods, droughts, soil that tires and rivers that move. Build slowly, read the land, and leave something that lasts.',
   array['A 4X with no combat, only land and time', 'Seasons and centuries that visibly reshape the valley', 'A hand-drawn map that fills in as your people explore']::text[],
   array['single-player', 'turn-based', 'relaxing', 'exploration']::text[],
   null),
  ('clockwork-orchard', 'Clockwork Orchard', 'Ink & Compass', 'Puzzle', 1799, 1799, '2026-05-21', 'tessera',
   'Wind the trees, set the gears, harvest at noon. A garden that runs on precise timing.',
   'The orchard is a machine: every tree a mechanism, every branch a gear. Set them turning so the fruit drops into the right baskets at the right moment. Forty gardens of escalating intricacy, and a sandbox where the only goal is something beautiful.',
   array['Forty clockwork gardens with hand-tuned solutions', 'A sandbox for building machines of your own', 'Share and play gardens made by other players']::text[],
   array['single-player', 'relaxing', 'controller']::text[],
   null),
  ('mirror-script', 'Mirror Script', 'Paper Lantern Co.', 'Puzzle', 1299, 1299, '2024-12-05', 'hex',
   'Every line of text in the world can be read backwards. Some of it means something else when you do.',
   'A word puzzle set in a library where every book carries a second meaning written in reverse. Flip sentences, rearrange shelves and uncover a story hidden between the lines. Designed with a dyslexia-friendly typeface and fully remappable controls.',
   array['Word puzzles built on reversal and rearrangement', 'A dyslexia-friendly typeface and adjustable spacing', 'Around six hours to finish, twelve to find everything']::text[],
   array['single-player', 'story-rich', 'relaxing']::text[],
   null),
  ('frostline', 'Frostline', 'Lowtide Games', 'Survival', 3999, 3999, '2025-12-11', 'frost',
   'Lay a railway across a frozen continent before winter closes the pass. Every mile is a fight with the cold.',
   'Your crew is building a railway across an ice sheet, and winter is coming for the pass behind you. Lay track, keep the boilers fed, and decide who goes out into the storm to fix what the frost breaks. Co-op for up to four.',
   array['Co-op survival for one to four players', 'A railway you build, maintain and depend on', 'Weather that closes routes for days at a time']::text[],
   array['co-op', 'crafting', 'challenging', 'atmospheric']::text[],
   null),
  ('canopy', 'Canopy', 'Two Rivers Collective', 'Survival', 2299, 2299, '2025-06-19', 'field',
   'Live in the treetops of a forest that has never touched the ground. Build up; never look down.',
   'The forest floor is a mist nobody returns from, so life happens in the canopy. Weave platforms between branches, trade with the glider-folk, and grow a home that sways with the wind. Survival that is cosy until, very suddenly, it is not.',
   array['Freeform building in a living, swaying canopy', 'Gliding traversal between the forest''s layers', 'Seasons that change what grows, and what hunts']::text[],
   array['single-player', 'crafting', 'exploration', 'relaxing']::text[],
   null),
  ('last-light-ferry', 'Last Light Ferry', 'Fourth Wall Works', 'Survival', 2799, 2799, '2026-01-15', 'lantern',
   'Keep the last ferry running across a river of fog. Passengers pay in whatever they have.',
   'One ferry, one river, and a fog that is getting thicker. Carry passengers between the last two towns, keep the engine alive on scavenged parts, and decide what a seat is worth when there are more people than places. Every crossing tells a story.',
   array['A survival management game on a single boat', 'Passengers whose stories unfold across crossings', 'Endings shaped by who you chose to carry']::text[],
   array['single-player', 'story-rich', 'atmospheric', 'crafting']::text[],
   null),
  ('the-lighthouse-letters', 'The Lighthouse Letters', 'Paper Lantern Co.', 'Narrative', 1699, 1699, '2025-04-10', 'map',
   'A lighthouse keeper''s letters, found forty years late. Answer them, and the replies start arriving.',
   'In the attic of a decommissioned lighthouse you find a box of letters from its last keeper — each addressed to someone who never received it. Write back, and the lighthouse begins to answer. A quiet, handwritten story about the people we lose track of.',
   array['A story told through letters you read and write', 'Around three hours, meant for a single sitting', 'Fully narrated, with an optional reading mode']::text[],
   array['single-player', 'story-rich', 'relaxing']::text[],
   null),
  ('undertow', 'Undertow', 'Sable & Stone', 'Narrative', 1949, 2999, '2024-10-17', 'tides',
   'A coastal town, a missing sister and a tide that returns things. A mystery in seven low tides.',
   'Your sister walked into the sea seven years ago. Now the tide is bringing her belongings back, one low tide at a time. Undertow is a branching mystery about a town that knows more than it says, told across seven days and seven retreating seas.',
   array['A branching mystery across seven in-game days', 'Choices that change which secrets the town will share', 'Illustrated in watercolour, scored for string quartet']::text[],
   array['single-player', 'story-rich', 'atmospheric']::text[],
   null),
  ('postcards-from-vega', 'Postcards from Vega', 'Brightwater Labs', 'Narrative', 1499, 1499, '2026-03-26', 'orbit',
   'A long-haul pilot sends one postcard home from every port. You decide what she writes.',
   'Mara flies freight between the stars and writes a postcard home from every port — to a family that ages faster than she does. Each stop is a small vignette; each postcard is a choice about what to tell them and what to keep. A gentle story about distance.',
   array['Twenty-four ports, each a hand-built vignette', 'A postcard system where what you leave out matters', 'Around four hours; replays reveal different lives']::text[],
   array['single-player', 'story-rich', 'sci-fi', 'relaxing']::text[],
   null),
  ('kite-weather', 'Kite Weather', 'Two Rivers Collective', 'Platformer', 1999, 1999, '2025-07-31', 'hex',
   'Ride the wind across a festival of kites. Every gust is a jump; every calm is a fall.',
   'A breezy platformer where you move by catching the wind. Ride gusts across rooftops, cling to kite strings, and chase the festival from town to town. Relaxed by default, with an unforgiving challenge route for anyone who wants one.',
   array['Movement built entirely around wind and momentum', 'A relaxed main route and a brutal challenge route', 'Local co-op for two on one screen']::text[],
   array['co-op', 'relaxing', 'pixel-art', 'controller']::text[],
   null),
  ('tin-soldier-hop', 'Tin Soldier Hop', 'Paper Lantern Co.', 'Platformer', 999, 999, '2024-09-12', 'cards',
   'A toy soldier crosses a nursery at night. The rug is a desert; the bookshelf is a mountain.',
   'A wind-up soldier sets out across the nursery after lights-out, and every piece of furniture is a level. Short, charming and precise, with every stage designed to be finished in under three minutes — and replayed for a better time.',
   array['Sixty bite-sized levels across a single nursery', 'Wind-up mechanics: your key runs down as you move', 'Online leaderboards for every level']::text[],
   array['single-player', 'pixel-art', 'controller']::text[],
   null),
  ('glasswing', 'Glasswing', 'Halfmoon Interactive', 'Platformer', 2499, 2499, '2026-08-20', 'frost',
   'A moth made of glass, a city made of light. Shatter and reform to cross the gaps.',
   'You are a glass moth in a city of lamps. Shatter into shards to slip through grilles, reform to fly, and use the light itself as a path. A precision platformer with a soundtrack that reacts to every movement.',
   array['Shatter-and-reform traversal with tight, forgiving controls', 'A soundtrack that follows your movement', 'Assist options for speed, damage and checkpoints']::text[],
   array['single-player', 'challenging', 'controller', 'atmospheric']::text[],
   null),
  ('night-market-tycoon', 'Night Market Tycoon', 'Brightwater Labs', 'Simulation', 2099, 2999, '2025-05-15', 'cards',
   'Start with one noodle cart. End with the loudest night market in the city.',
   'Open a single cart on an empty street and grow it into a sprawling night market. Hire cooks, set prices, keep the crowds fed and the rent paid, and find out which stalls pull customers from across the city. A cheerful management sim with a very long tail.',
   array['Grow a market from one cart to two hundred stalls', 'Customers with tastes, budgets and loyalties', 'A sandbox mode with no rent and no mercy']::text[],
   array['single-player', 'real-time', 'relaxing']::text[],
   null),
  ('orbital-freight', 'Orbital Freight', 'Nine Volt Games', 'Simulation', 3999, 3999, '2026-02-26', 'orbit',
   'Run a shipping company between moons. Orbits decide your schedule; fuel decides your margin.',
   'Plot cargo routes between moons whose orbits never stop moving. Every contract is a physics problem and a business decision at once: the fast route burns fuel, the cheap route takes months, and your rivals are bidding on the same cargo. Hard sci-fi logistics, made readable.',
   array['Real orbital mechanics behind every route', 'A freight economy with contracts, rivals and insurance', 'A co-op company mode for up to four players']::text[],
   array['co-op', 'sci-fi', 'real-time', 'challenging']::text[],
   null),
  ('greenhouse-9', 'Greenhouse 9', 'Lowtide Games', 'Simulation', 1899, 1899, '2025-02-20', 'field',
   'Tend the ninth greenhouse on a research station at the end of the world. The plants are listening.',
   'Grow crops in a sealed greenhouse on a polar research station: manage light, water and soil chemistry, and keep the station''s kitchen supplied through the long dark. The plants respond to how you treat them, and some of the station logs suggest they always have.',
   array['Deep, gentle growing systems grounded in real plant biology', 'A slow-burning mystery in the station logs', 'Seasons of polar day and polar night']::text[],
   array['single-player', 'relaxing', 'crafting', 'atmospheric']::text[],
   null),
  ('deckhands-descent', 'Deckhand''s Descent', 'Sable & Stone', 'Roguelike', 1999, 1999, '2025-08-07', 'cards',
   'Build a deck from the ship''s cargo and fight your way down into the hold. It goes deeper every run.',
   'A deck-building roguelike aboard a cargo ship whose hold has no bottom. Every crate you open adds a card; every run goes a little deeper. Combine cargo into combos, recruit a crew, and find out what the ship is really carrying.',
   array['Over three hundred cards drawn from the ship''s manifest', 'Crew members who change the rules of every run', 'A daily seeded run shared by every player']::text[],
   array['single-player', 'deck-building', 'procedural', 'challenging']::text[],
   null),
  ('vault-runner', 'Vault Runner', 'Meridian Forge', 'Roguelike', 2499, 2499, '2026-06-04', 'hex',
   'Heist a bank vault that rebuilds itself every time you are caught. Learn the vault, not the map.',
   'The vault is regenerated after every failed heist, but its rules persist: guards patrol in patterns, alarms chain, and locks remember how they were picked. A stealth roguelike about mastering systems rather than memorising layouts.',
   array['Stealth heists in a vault that rebuilds on every run', 'Persistent rules that reward understanding over memory', 'Two-player co-op heists online']::text[],
   array['co-op', 'procedural', 'challenging', 'pixel-art']::text[],
   null),
  ('hexfall', 'Hexfall', 'Nine Volt Games', 'Roguelike', 1499, 1499, '2025-01-23', 'hex',
   'A tactics roguelike on a board that falls apart as you fight. Every turn, three tiles drop into the dark.',
   'Fight on a hexagonal board that crumbles beneath you: every turn, tiles fall away and the arena shrinks. Position is everything, retreat is temporary, and knocking an enemy into the void is always an option. Short runs, deep decisions.',
   array['Turn-based tactics on a collapsing hex board', 'Twenty-minute runs that teach you something every time', 'Over forty unlockable units and abilities']::text[],
   array['single-player', 'turn-based', 'procedural', 'challenging']::text[],
   null),
  ('ninefold', 'Ninefold', 'Halfmoon Interactive', 'Roguelike', 3499, 3499, '2026-09-10', 'crown',
   'Nine lives, nine worlds, one tower. Every death sends you somewhere new.',
   'Climb a tower that exists in nine worlds at once. Each death shifts you into the next — a different world, the same tower, and everything you learned still true. Fast, fluid combat and a story that only makes sense on the ninth life.',
   array['Nine interlocking worlds, one continuous climb', 'Fluid melee combat with a deep move set', 'A story revealed across deaths rather than despite them']::text[],
   array['single-player', 'fantasy', 'procedural', 'challenging', 'controller']::text[],
   4);
