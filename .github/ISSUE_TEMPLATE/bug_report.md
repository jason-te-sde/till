---
name: Bug report
about: Something does not do what it says it does
labels: bug
---

## What happened

## What you expected

## How to reproduce

If the simulator found it, the seed is enough and is worth more than anything else here:

```
mvn test -pl till-testkit -Dtill.sim.seeds=<n> -Dtest=SoakTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Otherwise: the commands, the request bodies, and the responses. A `tillctl` transcript is ideal.

## Which invariant, if one broke

`InvariantViolation` names the property, the seed and the step. Paste the whole message; it contains
the numbers that prove it broke.

## Environment

- till version:
- JDK:
- PostgreSQL:
- Running as: jar / container / embedded library
