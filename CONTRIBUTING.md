# Contributing

## Prerequisites

- JDK 21 or newer, Maven 3.9 or newer.
- Optional but strongly recommended: Docker, or a PostgreSQL server you can point the tests at.
  Without one, the adapter and integration suites **skip**, and about a quarter of the Java tests
  with them.
- Node 24 or newer, **only if you are touching the console**. A plain `mvn verify` does not need it.

```bash
mvn verify                                  # the kernel, the simulator, the client
TILL_TEST_DB_URL=jdbc:postgresql://localhost:5432/postgres \
TILL_TEST_DB_USER=me TILL_TEST_DB_PASSWORD=me mvn verify   # everything Java

cd till-web && npm ci && npm run check      # the console: typecheck, lint, unit tests
```

`TILL_TEST_DB_URL` names a **server**, not a database. Each module creates one of its own on it.

### Working on the console

```bash
# a service to talk to
java -jar till-server/target/till-server-0.1.0.jar \
    --server.address=127.0.0.1 --till.auth.admin-token=dev

# and the console, proxying /v1 to it
cd till-web && npm run dev            # http://127.0.0.1:5173

# end to end, against a service that is already running
TILL_API=http://127.0.0.1:8080 TILL_TOKEN=dev npm run e2e
```

`mvn -Pweb package` builds the console into the server jar, which is what CI and the release do. A
plain `mvn package` does not, and the service says so at startup rather than answering 404 without
explanation.

**If you change an endpoint,** regenerate the contract and the types and commit both:

```bash
mvn -pl till-server test -Dtest=OpenApiContractTest -Dtill.openapi.write=true \
    -Dsurefire.failIfNoSpecifiedTests=false
cd till-web && npm run api:types
```

CI fails if either is stale, because a console compiled against types for an API that no longer
exists finds out in a browser.

## Before you push

```bash
scripts/preflight.sh
```

This runs `mvn verify` on **every JDK in the CI matrix**, currently 21 and 25.

Running it on one JDK is not enough to predict CI. The build compiles with `-Werror`, and lint
categories change between releases, so a comment or a construct that is silent on 21 can fail on 25.
The script fails if it cannot find a JDK in the matrix rather than skipping it, because a preflight
that checks half the matrix gives you the confidence without the coverage.

Arguments are passed through to Maven, so `scripts/preflight.sh -DskipTests` compiles on both without
running the suite.

## Workflow

1. **Open an issue first** for anything that is not a typo. It states the problem, the proposed
   approach, and what it would cost. A pull request is a much more expensive way to have a
   disagreement about design.
2. Branch from `main`. Name it after the thing, not after yourself: `expiry-on-commit`, not
   `jason/fix`.
3. One change per pull request.
4. `scripts/preflight.sh` passes.
5. The pull request says what it changes and why, and how you know it works.

`main` is always releasable. There is no develop branch and no release branch, because there is one
maintainer and a second long-lived branch would only ever be merged by the same person who forked it.

## Commit messages

```
till-jdbc: strip comments before splitting the schema on semicolons

A comment in the schema contained a semicolon, so splitting first cut it in
half and glued its second half to the front of the next statement. The
adapter suite failed with "relation till_outbox does not exist" fifteen
tests later.

JdbcSchemaTest now asserts every statement begins with `create`.
```

- **Subject: a module prefix, then what changes, in the imperative, under 72 characters.** The prefix
  is the module (`till-core:`, `till-web:`) or `docs:`, `build:`, `ci:`.
- **Body: why, not what.** The diff already says what. What it cannot say is what went wrong, what
  else was considered, and what it costs.
- **Say how you know.** Which test fails without this, or which seed reproduced it.
- Wrap at 80. `git log` is read in a terminal.

A commit that needs "and also" in its subject is two commits.

## What a change to the rules needs

A change to `till-core` is a change to what the project promises, so it needs more than a passing
build:

- **A test that fails before it.** If the simulator found it, say which seed, and add the reduced
  case to the unit suite so the next person sees the rule rather than the symptom.
- **An invariant, if the change makes a new one possible.** `Invariants` is the project's actual
  specification; it is worth more than a paragraph in a document.
- **A note in `docs/design/` if it changes a decision**, not only the code. The format is: what was
  the context, what was decided, what it costs, and what was rejected. The costs section is the one
  that matters — a decision note without one is an advertisement.

## Style

There is no formatter config, because there is no formatting argument worth having. Match what is
around you: four spaces, 118 columns, no wildcard imports, `final` on fields that are.

Two things are not style and will be asked about in review:

**Comments say why, not what.** `// increment the version` is noise. `// Assigned last, and this is
the point: assigning it before the schema existed meant a failure here was reported once and then
hidden` is a comment that earns its line. If a comment restates the code, delete it; if the code
needs one and you cannot write it, the code is the problem.

**A test's name says what it protects.** `testReserve` is a name that tells the next person nothing
when it goes red. `a reservation over several SKUs takes all of them or none` tells them what broke.

## What to expect from review

Slow, and detailed. This is one person's side project, so a first response might take a week. When it
comes, expect questions about why rather than requests to change formatting — and expect that "I did
it that way because X" is usually the end of the discussion.

Things likely to be pushed back on:

- A new dependency in `till-core`. It has one, on purpose.
- A rule that lives outside `Kernel`. The simulator cannot reach it there.
- A test that sleeps. Time is injectable everywhere in this project; if it is not injectable in the
  new place, make it so.
- Anything in `till-server` that makes a decision. That layer parses a request, runs one command, and
  turns an outcome into a status code.
- A runtime dependency in `till-web`. It has three — React, its DOM renderer, and a router — and a
  client library shipped into somebody's page is the wrong place for a fourth.
- An idempotency key generated inside a React component. It regenerates on every render, and a new
  key is a new order. Keys are minted in an event handler and passed down; `src/api/idempotency.ts`
  is the only place that makes them.
- A hand-written TypeScript type for an API response. They are generated from `openapi.json`.

## What is out of scope

[`docs/design/0005-scope.md`](docs/design/0005-scope.md) is the list, with a reason for each. If you
want something on it, the issue to open is about the reason rather than about the feature.
