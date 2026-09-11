# How till is tested

Seven layers. Each one covers what the cheaper layer below it structurally cannot, and the list of
what none of them covers is at the bottom, because a testing document that only lists strengths is
marketing.

| Layer | What it can see | What it cannot |
| --- | --- | --- |
| **Unit** | one rule, one state transition, one boundary | anything about two callers |
| **Deterministic simulation** | interleavings, crashes, lost answers, clock jumps — every invariant after every step | the database, and the Java memory model |
| **Differential** | the two ledgers disagreeing about anything at all | a bug both of them share |
| **Real concurrency** | that PostgreSQL's conditional update and unique constraint do what the design assumes | more than a handful of schedules |
| **Integration** | the wiring: Flyway, Spring's binding, the filter order, an `Instant` surviving Jackson and `timestamptz` | anything below the HTTP layer, which the faster layers already cover |
| **Console unit** | the checkout state machine, the retry-with-the-same-key rule, what each refusal looks like on screen | whether the service agrees |
| **End to end** | a real browser against the jar that ships, including two tabs racing for the last unit | anything it cannot click |

## The simulator

A command is not one operation. It is three phases — **load** a snapshot, **decide** against it, try
to **apply** — and the simulator advances one phase of one caller per step, choosing which caller by
seed. That is what makes the races real: between one caller loading and the same caller applying, any
number of others can have decided and applied against rows it now holds a stale view of.

Threads would produce the same races, and would produce different ones every run.

On top of the interleaving it injects the four things that actually go wrong:

| Injected | Why it is the interesting case |
| --- | --- |
| A process dies between deciding and applying | nothing may be half-written, and the retry must not double-count |
| An answer never reaches the caller | the command *was* applied; the retry has to be recognised. This is the case idempotency exists for, and the one a hand-rolled implementation gets wrong |
| The clock jumps | holds expire in bunches, the way they do when a process stalls |
| A caller walks away | its hold is left to expire, which is what closing a browser tab looks like |

Everything about a run is a function of `SimConfig`, and `SimConfig` is mostly the seed. A failure at
seed 8123 is still there at seed 8123 tomorrow, on another machine, in another JDK.

## The invariants, and what each one catches

Checked after **every step**, not at the end. A ledger that oversells ten units and then has them
released looks perfectly balanced by the time a run finishes, and an end-state assertion would pass
with the bug still in it.

| Invariant | The failure it catches |
| --- | --- |
| **Possible levels** — `0 <= reserved <= onHand` | the direct statement of "never oversold" |
| **Conservation** — a SKU's `reserved` equals the sum of every `HELD` reservation | a hold counted twice, or not at all |
| **The ledger agrees with its own audit log** — `onHand` equals adjustments minus commits in the outbox, and `reserved` equals reservations minus commits, releases and expiries | a change written without its event, or an event written for a change that did not happen. The two failure modes a transactional outbox exists to prevent, and the two that otherwise show up months later as a downstream system that has quietly drifted |
| **Terminal states are terminal** | a committed reservation being released, or a hold being re-taken |
| **Versions never go backwards** | an optimistic check comparing against the wrong number |
| **Deduplication keys are unique** | a consumer silently dropping a real event |
| **Events match states** | a committed reservation with no committed event |

## What the invariants structurally cannot see

They ask whether the ledger is consistent with itself. A ledger can be flawlessly consistent while
handing a caller an answer that was never true: told its hold was created when nothing was written,
told the same key two different things, told stock left when it did not. Those are the failures a
customer notices and none of them is visible from inside.

So `History` keeps every answer a caller received and, once the run has drained, checks it:

- **One key, one answer.** Every observation under an idempotency key is identical, including the
  reservation id inside it. This is the property that fails the moment a retry becomes a second order.
- **A hold that was promised exists**, with the lines and the deadline the caller was told.
- **A commit that was promised happened.**
- **Nothing left the building unannounced.** Every committed reservation was observed as committed by
  somebody.

The run is **drained** before these are checked — every caller finishes what it started, with no new
commands and no injected faults. Without that, the final-state checks would have to tolerate a ledger
that is mid-operation, which means tolerating exactly the states a real bug produces.

## Every chaos test asserts the run was hostile

The failure a simulation is most exposed to is a schedule that happens to be tidy: it passes every
invariant, and it would go on passing after the concurrency control was deleted. So `SimReport`
counts what a run actually did, and `SimTest` asserts on it — conflicts, replays, injected crashes,
lost answers, expiries, sweeps, sales, and refusals for want of stock all have to have happened.

One seed of the default configuration produces roughly: 465 conflicts, 48 replays, 54 injected
crashes, 36 lost answers, 91 expiries, 52 sweeps, 48 sales, 37 refusals.

## Proof that the suite would notice

A green run says something about the code only if the tests can go red. `FlawDetectionTest` puts back
four mistakes a hand-written implementation of this problem plausibly makes and asserts that the
simulator fails — and **which check** fails, so that a change which quietly turns one of these into a
different symptom shows up in the diff.

| Flaw | The mistake | Caught by |
| --- | --- | --- |
| `LOST_UPDATE` | expected versions are not checked, so the later of two decisions wins | The ledger agrees with its own audit log |
| `NO_IDEMPOTENCY` | a recorded outcome is never returned, so a retry runs twice | Deduplication keys are unique |
| `PARTIAL_APPLY` | mutations are written and the events beside them dropped | The ledger agrees with its own audit log |
| `RESERVED_IGNORED` | availability read as `onHand`, i.e. `stock >= quantity`, which is how every tutorial writes it | the kernel refusing to build an impossible value |

Each is caught within the first few thousand steps, and on all twenty seeds the test sweeps, not on
one lucky one.

Two mistakes are deliberately **not** in that list, because the design makes them harmless and it is
worth knowing which:

- **Applying a decision twice.** Mutations carry absolute values rather than deltas, so writing them
  again writes the same numbers.
- **A ledger that offers a live hold as reclaimable.** The kernel re-checks the deadline itself,
  because it does not trust the adapter's filtering.

## The differential test

The same seeded schedule against `InMemoryLedger` and `JdbcLedger`, compared row for row: stock,
reservations, outbox, and every answer given along the way.

Worth more than either implementation's own tests. The two were written from one contract by one
person, so they are wrong in the same places only where the contract itself is unclear — and they
disagree exactly where one of them read it differently. Ordering, boundary conditions, what counts as
a conflict, whether an absent row is the same as an empty one: every one of those is a place two
implementations drift, and none of them is visible from inside either.

Because the schedule is a function of the seed *and of the answers it gets*, identical final states
are also evidence that every answer matched. A divergence at step 40 changes what the caller does at
step 41, and the run comes apart from there.

It also found a real difference: PostgreSQL orders `varchar` by the database's collation, which for
`en_US.UTF-8` is not code point order, so the two ledgers offered expired holds to the kernel in
different orders. The adapter's `order by` clauses are `collate "C"` because of it.

## Real threads, real database

The simulator covers interleavings far more thoroughly, because it chooses them. What it cannot cover
is whether PostgreSQL's conditional update really is atomic, whether the unique constraint really
does refuse the second insert, and whether the Java memory model publishes what the in-memory ledger
wrote.

- 200 callers, 20 units, and exactly 20 sales.
- The same request sent 30 times at once, executed once, with all 30 given the same answer.
- Holds, commits and cancellations at once, with on-hand falling by exactly what was committed.

The in-memory versions of these assert `conflictCount() > 0`: a hundred threads on one row that never
conflicted did not exercise the retry loop, however many threads were started.

## The database is the last line

`till_stock` carries `check (reserved >= 0 and on_hand >= 0 and reserved <= on_hand)`. A test writes
a bad level with raw SQL and asserts PostgreSQL refuses it with SQLState 23514. That constraint
cannot be bypassed by a bug in the application, by a migration script, or by somebody fixing data by
hand at three in the morning — and `apply` deliberately does **not** treat a check violation as a
conflict, because retrying it would hide the bug that produced it behind a loop that never
terminates.

## The console

Two suites, and the division is the same one as on the Java side: the fast one covers the rules and
the slow one covers the wiring.

**Unit (Vitest, Testing Library, a request interceptor).** The HTTP layer is mocked at the network
rather than by stubbing `fetch`, so the tests assert on what actually went over the wire — which is
where the property worth asserting lives. The centre of it is `useCheckout`: one attempt key per
attempt, reused by every retry within it, with each step deriving its own from it. Clicking Pay twice
sends one key. Retrying a refused basket sends the same key. Starting a second attempt sends a new
one. Every one of those decides whether somebody is charged once or twice, and none of them is
visible in a screenshot.

An unhandled request fails the test rather than returning nothing, because a test that silently gets
no answer is a test that passes for the wrong reason.

**End to end (Playwright).** Against `vite preview` locally and against the **jar with the console
bundled into it** in CI, which is the artifact a release ships. Serving the bundle from a separate dev
server would leave the service's own route forwarding untested.

What it checks that nothing else can:

- A hold sets stock aside without selling it; paying sells it. Both numbers read back off the page.
- Clicking Pay twice produces one sale.
- **Two browser contexts want the last unit and exactly one gets it**, and the other is told by how
  much it fell short. Two contexts means two session stores and two cookie jars — as close to two
  people as a test gets.
- The operator view shows the hold the shop just took, in both of its states.
- A filter button asks the service rather than filtering a page of fifty in the browser.
- A reloaded deep link is served by the service, and a path the console does not have is still a 404.

The suite **skips** when there is no service to talk to, and **fails** when `CI` is set and there is
none — the same policy as the PostgreSQL suites, for the same reason.

**The contract between the two halves is tested.** `till-web` generates its types from a committed
`openapi.json`; `OpenApiContractTest` regenerates that file from the running service and fails if the
committed copy has drifted, and CI re-runs the generator and fails on a diff. Without both, a change
to an endpoint leaves the console compiling against types for an API that no longer exists, and
finding out in a browser.

## Running it

```bash
mvn verify                       # everything Java; the PostgreSQL suites skip without a database
mvn verify -Dcoverage            # plus JaCoCo

# The PostgreSQL suites want a server. Docker, or one you already have:
TILL_TEST_DB_URL=jdbc:postgresql://localhost:5432/postgres \
TILL_TEST_DB_USER=me TILL_TEST_DB_PASSWORD=me mvn verify

# The console
cd till-web
npm ci
npm run check                    # typecheck, lint, unit tests

# End to end, against a service that is already running
TILL_API=http://127.0.0.1:8080 TILL_TOKEN=... npm run e2e
```

`TILL_TEST_DB_URL` names a **server**, not a database: each module creates one of its own on it, so
that the module managing its schema with `JdbcSchema` and the module managing it with Flyway cannot
leave each other a schema the other does not expect. That was not a hypothetical — see the bug list
in the README.

Without Docker and without that variable, the PostgreSQL suites **skip** locally and **fail** in CI.
Skipping locally is a kindness; skipping in CI would turn "the adapter was never tested on this
change" into a green tick.

The soak is off by default because it is minutes rather than seconds:

```bash
mvn test -pl till-testkit -Dtill.sim.seeds=10000 -Dtest=SoakTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

## What this does not cover

- **No torn writes.** Crashes are injected between phases, not between bytes. A PostgreSQL transaction
  is atomic by assumption here, not by test.
- **No process kill.** The simulator models a process dying between deciding and applying; nothing
  actually sends a signal to a running server mid-transaction.
- **No clock skew between instances.** Every instance is assumed to read roughly the same wall clock.
  Two servers a minute apart would disagree about which holds have expired, and nothing here would
  notice. See the ADR on deadlines for why that is survivable and what it costs.
- **No adversarial input fuzzing at the HTTP layer.** Identifiers are validated at the boundary and
  the JSON reader is strict and tested, but nobody has pointed a fuzzer at either.
- **No load test.** There are no published throughput numbers for the service, only for the simulator
  and the suite. Publishing a figure measured on one laptop would say more about the laptop.
- **The container job checks that the stack works, not that the image is small or safe.** Nothing
  scans it, nothing measures it, and nothing checks that the base image is current beyond Dependabot
  raising a pull request when it is not.
- **Multi-line reservations are covered by the invariants but not by the history checker's
  serialisability reasoning**, which is per-key. See the note in `History` for why a full
  linearizability search is not attempted.
- **One browser.** The end-to-end suite runs Chromium only. Nothing here is doing anything a
  rendering engine disagrees about, but that is an argument rather than a test.
- **No visual regression testing.** Light and dark mode were looked at by a person, once. A palette
  change that ruins the contrast of one chip would not fail a build.
- **No accessibility audit tool.** Roles, labels and the "colour is never the only channel" rule are
  asserted by hand in the component tests; nobody has run axe over the pages.
