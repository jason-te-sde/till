# 4. A deadline decides, not a stored state

**Status:** accepted

## Context

A hold lasts fifteen minutes. Something has to notice when it runs out.

The obvious design is a background job: every few seconds, find held reservations past their
deadline, mark them `EXPIRED`, and give their units back. Everything else reads `state` out of the
row and trusts it.

The problem is what happens in between. A hold that expired four seconds ago and has not been swept
yet is still `HELD` in the database. So:

- A commit on it succeeds, even though the customer was told they had fifteen minutes and used
  twenty.
- Its units still count against `available`, so the next customer is told there is no stock when
  there is.
- Both answers depend on whether a job happened to have run in the last few seconds, which is not a
  property a caller can reason about — and not one a test can reproduce, because the test would have
  to control the scheduler.

## Decision

**The deadline is the truth. The stored state is a cache of it.**

`Reservation.effectiveState(now)` returns `EXPIRED` for a held reservation whose deadline has passed,
whatever the row says. Every decision uses that, and reading `state()` directly anywhere a decision
is made is the bug this rule exists to prevent.

Every command also **writes off the expired holds standing in its way** as it passes. A reservation
that fails for want of stock while an expired hold sits on it would be wrong, and telling the caller
to run a sweep first would make the answer depend on operational luck. The reclaim is scoped to the
SKUs the command is about: writing off an unrelated hold would touch a row the command has no reason
to touch and turn an unrelated caller's commit into a conflict.

The sweeper therefore **returns stock to `available` sooner and never makes a wrong answer right**.
Turning it off makes stock come back later, never never.

## Consequences

Correctness does not depend on a scheduler. That is the whole point, and it also means the sweeper
can be disabled, can fail, can run on every instance at once, and can be behind by minutes, without
any of it being a correctness question. `till.sweeper.enabled=false` is a supported configuration.

The API reports both states, because they differ and a caller can see the difference:

```
$ tillctl get 75dc0fa9-9541-4b98-9939-845a4dd6aeab
75dc0fa9-...  EXPIRED (stored HELD)  created ...  expires ...  widgetx1
```

A commit on an expired hold is **410 Gone** rather than 404 or 409: the reservation existed, the
caller had it, and it is no longer available, which is what 410 means.

A rejection can carry mutations. Refusing that commit also writes the expiry off, because the kernel
has just established it. The first bug the simulator found was in exactly this path: `effectiveState`
collapses "held but past its deadline" and "written off an hour ago" into one answer, and the commit
path acted on the answer rather than on the stored state, giving the same units back twice.

**Clock skew between instances is not handled.** Two servers a minute apart disagree about which
holds have expired, and neither the simulator nor the suite would notice. What it costs: a commit
might be refused by one instance and accepted by another within the skew window, and a hold might be
reclaimed a minute early, handing its units to somebody else while its owner still believes they have
them — which is an oversell in the sense that matters to a customer, though not in the sense the
invariants check, because the ledger stays balanced. Real deployments run NTP and skew is
milliseconds; till assumes that rather than defending against it, and `docs/testing.md` lists it as a
gap.

## Alternatives

**Sweep only, and trust the stored state.** Rejected above. It is the common implementation and the
reason "the stock says zero but nothing is held" is a familiar complaint.

**No expiry: holds last until released.** Simpler, and it means an abandoned checkout holds stock
forever. Every real system has a deadline.

**Expire by deleting the row.** Then `reserved` has to be corrected in the same transaction anyway,
and the history of what was held and by whom is gone — which is the first thing anybody asks for when
stock does not add up.

**A database trigger or a scheduled job inside PostgreSQL.** Moves the rule somewhere the simulator
cannot reach, and somewhere a reader of the Java will not look.
