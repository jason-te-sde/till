## What this changes

## Why

## How you know it works

Which test fails without this change? If the simulator found the bug, which seed?

## Checklist

- [ ] `scripts/preflight.sh` passes (JDK 21 and 25)
- [ ] The PostgreSQL suites ran — Docker, or `TILL_TEST_DB_URL` set. They skip silently otherwise
- [ ] A test that fails without this change
- [ ] If this changes a decision rather than an implementation, a note in `docs/design/`
- [ ] If this changes a wire format, a schema, or the `Ledger` contract, `CHANGELOG.md` says so
