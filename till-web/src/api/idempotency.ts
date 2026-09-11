/**
 * A key names **an attempt**, not a request.
 *
 * Every retry of one attempt carries the same key, which is what makes retrying safe; a new attempt
 * at the same thing gets a new key, which is what stops a customer being unable to buy a second one.
 * Getting that backwards in either direction is the bug idempotency exists to prevent, so the
 * distinction lives in a named function rather than in a `crypto.randomUUID()` at each call site.
 */
export function newAttemptKey(purpose: string): string {
  return `${purpose}-${crypto.randomUUID()}`
}

/**
 * The key for a step of an attempt that already has one.
 *
 * A checkout is one attempt with several steps — take the hold, then pay for it — and each step is
 * idempotent on its own. Deriving the step's key from the attempt's means a double-clicked Pay
 * button sends the same key twice and produces one sale.
 */
export function stepKey(attemptKey: string, step: string): string {
  return `${attemptKey}.${step}`
}
