# till-loadtest

The load test's two moving parts. [`docs/load-test.md`](../docs/load-test.md) is the protocol —
what is measured, what counts, the targets — and the results.

- **[`k6/shopper.js`](k6/shopper.js)**, the scenario: eight thousand customers arriving, browsing,
  searching, and one time in ten signing in, ordering and paying.
- **[`StandInProvider`](src/main/java/io/till/loadtest/StandInProvider.java)**, the OpenID provider
  they sign in with. It approves every request without a password — so it runs only inside the load
  test's VPC, and refuses to start unless it has been configured on purpose — and otherwise behaves
  like a provider: a code per request, redeemable once, by the right client, with the right PKCE
  verifier, for an RS256 ID token signed by the key it publishes. The store's whole side of signing
  in runs against it. JDK only.

Both ship in one image, the Dockerfile's `loadtest` target: `k6 run /loadtest/k6/shopper.js` is the
load generator and `java -jar /loadtest/till-loadtest.jar` the provider.

## On a laptop

For trying the scenario, not for measuring anything:

```bash
docker compose -f docker-compose.yml -f till-loadtest/compose.yml up -d --build --wait
docker compose -f docker-compose.yml -f till-loadtest/compose.yml run --rm k6
SHOPPERS=200 HOLD=2m docker compose -f docker-compose.yml -f till-loadtest/compose.yml run --rm k6
```

The override points the store at the stand-in, stocks every game without limit, and lets the edge
believe each shopper's address. The scenario is mounted from the working tree, so a change to it is
tried by running again.

## On AWS

```bash
scripts/aws.sh up --loadtest
scripts/aws.sh loadtest --shoppers=100 --ramp=20s --hold=1m   # a minute, to prove the path
scripts/aws.sh loadtest                                        # the protocol's run
scripts/aws.sh down
```

Each run's result lands in [`results/`](results), with the commit it ran and what CloudWatch said
about the same window.
