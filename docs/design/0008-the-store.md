# 8. A store in front of the ledger: a backend-for-frontend, an edge, and Redux

**Status:** accepted. Supersedes [7](0007-browser-console.md).

## Context

The console of [decision 7](0007-browser-console.md) did its job — it showed why holds are worth
having — and it was not a store. It asked a person to paste a bearer token, it kept that token in
`sessionStorage`, its catalogue was a list of made-up hardware compiled into the bundle, and the page
a customer would use sat one tab away from the page that can zero the inventory. Decision 7 said so,
and said a product would put a server in front.

This is that server, and the store that goes with it. What it had to be:

- **A shop somebody could believe in.** Real games with prices and discounts, search, a page per
  game, a cart, checkout, order history.
- **Signed in, properly.** Customers are people with accounts at an identity provider — Amazon Cognito
  in production — and operators are a group of them, not a second secret.
- **Unable to oversell, still.** The store must not become a second authority on stock.

## Decision

**A backend-for-frontend, and the browser holds no token.** `till-store` runs the OpenID Connect
authorization-code flow with PKCE on the server, keeps the tokens in the session, and gives the
browser an `HttpOnly`, `SameSite=Lax` cookie. Every write also carries `X-XSRF-TOKEN`, copied from a
cookie only a script on the store's own origin can read. Operators are the provider's `admins` group,
mapped to a role that `/api/ops` requires.

**Sessions in Redis.** Any instance can serve any request, and the non-indexed session repository
needs no keyspace notifications, so it runs on ElastiCache, which forbids the `CONFIG SET` the
indexed one issues.

**Keycloak locally, Cognito in production, one code path.** The local realm names its groups claim
`cognito:groups`, so the mapping from groups to roles is the same code against both. Locally the
provider's endpoints are configured explicitly, because the browser and the store's container reach
Keycloak at different addresses and one discovery document cannot be right for both; the issuer is
still checked against every ID token.

**The store cannot move stock.** It reserves, commits and releases through the ledger's public API
with a client token, on a database of its own. Availability on a store page is a projection of the
ledger's events — allowed to lag, stamped with the ledger's clock, never consulted when a sale is
decided. Orders converge on the ledger's answer whichever way the news arrives, by HTTP or by event,
and every transition is guarded by the state it expects.

**A customer's idempotency key never reaches the ledger as sent.** The ledger's key space is global;
a browser's is not. The store sends a digest of the purpose, the customer, the order and the key.

**An edge proxy, and one origin.** nginx serves the SPA's files and proxies the store; the ledger is
not reachable through it. It owns the security headers — including a Content-Security-Policy with no
inline script — the rate limits, and a five-second microcache for the catalogue whose staleness bound
the store sets.

**Redux Toolkit, and RTK Query for everything fetched.** The cart is read by four unrelated parts of
the page and rewritten by checkout when the ledger reports a shortfall, so it is a slice. Server state
is RTK Query endpoints with cache tags, so "paying for an order makes the order list stale" is one
declaration rather than a line in every component that pays. The two headers that are the SPA's whole
security story are set in one `prepareHeaders`.

**Cover art is generated.** Each game names one of twelve motifs, and its SKU seeds the palette and
layout of an SVG scene. Thirty-two distinct covers, a few kilobytes each, sharp at any size, and no
image the project does not own.

## Consequences

- **Two more moving parts to run** — Redis and an identity provider — and an edge. The compose file
  runs all of them, with health checks, so `docker compose up --wait` is still one command.
- **Signing in is a round trip through the provider's own page**, which a unit test cannot drive. The
  store's `SignInTest` runs it against a small in-process provider that is strict about PKCE, nonces,
  redirect URIs and signatures; the end-to-end suite runs it in a browser against Keycloak.
- **The SPA cannot be hosted on another origin** without giving up the cookie's `SameSite` protection
  and taking on CORS. That is the point of the edge.
- **More JavaScript.** Redux Toolkit and its React bindings roughly double the runtime dependencies of
  the SPA, to five. The bundle is 139 kB gzipped, with the operator console split out.
- **Payment is simulated.** Paying commits the hold; no money moves, and the page says so. Taking real
  payment would add a payment provider between placing an order and committing it, and nothing else
  here would change.

## Rejected alternatives

- **Tokens in the browser** (a public client, tokens in memory or storage, calling the API with a
  bearer header). Simpler to wire, and every script that ever runs on the page can read the token and
  post it anywhere. A cookie the page cannot read cannot be exfiltrated by a script at all.
- **The SPA calling the ledger directly for stock.** The ledger would need CORS, a token the browser
  holds, and a notion of who a customer is. It would stop being a ledger.
- **Sticky sessions instead of Redis.** Works until an instance is replaced, when everybody on it is
  signed out mid-checkout.
- **React context and `useState` for the cart.** Adequate for one reader. Four readers and a writer
  in checkout make it a store with none of a store's tools.
- **A catalogue cache in Redis now.** The edge's microcache already turns a thousand identical
  requests a second into one every five seconds, and nothing has been measured that says the
  database needs more. A read cache comes with a measured baseline, or not at all.

  *Later:* the baseline came. The second load test ([`docs/load-test.md`](../load-test.md)) found the
  catalogue's searches and tag counts taking more than half of the database's CPU at 8,000 shoppers,
  and a checkout queueing behind them for a connection; `CatalogueCache` is the cache, and the load
  test measures it against its absence.
