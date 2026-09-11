# 7. One console, two audiences, served by the service

**Status:** accepted

## Context

till is a service, and a service with no screen is a service nobody believes. Two different people
need to see something:

- Somebody deciding whether reservations are worth the complexity. What convinces them is not a
  stock table — it is watching two browser tabs race for the last unit and seeing exactly one of
  them get it, and watching a hold count down and hand its stock back.
- Somebody running it. What they need is what the ledger holds, which holds are open, and whether
  the outbox is keeping up.

Those are different screens, and the temptation is to build the second and call it done, because it
is the one an inventory service "should" have. The first is the one that explains the project.

## Decision

**One application, two routes.** `/shop` is a shop front with a catalogue, a basket, a hold with a
visible countdown, and a Pay button. `/ops` is the operator view: totals, a stock table, the
reservations with their states, and the outbox backlog.

React, TypeScript and Vite. Served by `till-server` from `/static`, built by an **opt-in** Maven
profile (`-Pweb`).

**The catalogue lives in the frontend.** Names, prices and pictures are the shop's business; how many
there are is the ledger's. till has no product table by design, and this is the demonstration of
that rather than a gap in it.

**Types are generated from a committed `openapi.json`**, which a server test regenerates and guards.
Hand-written types drift from the API silently; generating at build time from a live service makes
the build need a running service. A committed document with a test that fails when it goes stale has
neither problem.

**No serialisation library.** The client is `fetch` and the generated types.

## Consequences

**The Maven profile is off by default, and that is the trade.** On by default, every contributor to
the kernel needs a working Node toolchain to run `mvn verify`. Off by default, a plain build produces
a jar with an API and no console — which the service says at startup rather than leaving somebody to
find a 404. CI and the release both build with the profile, so what ships always has it.

**The route forward is enumerated, not a catch-all.** A single-page application reloaded on
`/ops/reservations` arrives as a request for a path no controller has, and the usual answer is a
catch-all forward to `index.html`. The usual bug that comes with it is that the catch-all also
swallows `/v1/nonsense` and returns an HTML page, which a client parses as JSON and reports as a
corrupt response. Only the routes the console has are forwarded; there is a test for the difference.

**The console asks a person for a bearer token,** and keeps it in `sessionStorage`. That is the
honest minimum for a console and it is not what a product would do: a product would sign somebody in,
keep a session cookie the page cannot read, and put a small server in front that holds the token. That
server is out of scope, and shipping something that looks like a login and is not would be worse than
saying so. `sessionStorage` rather than `localStorage` because the lifetime is right — closing the
tab is a logout everybody already knows how to perform — not because it is meaningfully safer against
a script on the page.

**CORS is off, and a wildcard is refused.** The console is same-origin, so it needs none.
`till.web.cors-origins` exists for somebody hosting it separately and names origins; `*` raises at
startup, because an inventory API any page may call is an inventory API any page may read.

**Two more toolchains in CI.** A Node matrix of two versions, and a Playwright job that builds the
jar with the console in it and drives the real service. That is most of the added build time and it
is where the claims in the README get checked.

## Alternatives

**An operator console only.** What an open-source inventory service would ship, and it cannot show
why reservations exist — the countdown and the refusal are the argument.

**A shop demo only.** Closer to the project this one is modelled on, and it hides the ledger, which
is the thing being built.

**Server-rendered pages — Thymeleaf, htmx.** No second toolchain, no bundle, no Node in CI. Rejected
because the two things worth demonstrating are both live: a countdown that has to tick and two tabs
that have to see each other. Both are possible server-rendered and neither is pleasant.

**A separate deployable, hosted anywhere static.** Then `docker compose up` gives an API and a
sentence about where to get the console, and the CORS question becomes mandatory rather than optional.
Bundling is one artifact and one origin.

**Next.js.** A server-rendering framework for a console with no server-rendering requirement, whose
own runtime would then need deploying beside the service it exists to show.

**A hand-written TypeScript client.** Considered, and it is what the Java client does. The difference
is that the Java client's types are checked against the service by a test suite in the same build;
a hand-written TypeScript type is checked against nothing.
