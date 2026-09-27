/**
 * A key names **an attempt**, not a request.
 *
 * Every retry of one attempt carries the same key, which is what makes retrying safe; a new attempt
 * at the same thing gets a new key, which is what stops a customer being unable to buy a second copy
 * next week. Getting that backwards in either direction is the bug idempotency exists to prevent, so
 * the distinction lives in a named function rather than in a `crypto.randomUUID()` at each call site.
 *
 * @param purpose what the attempt is, for reading keys in a log
 * @returns a key no other attempt will use
 */
export function newAttemptKey(purpose: string): string {
  return `${purpose}-${crypto.randomUUID()}`
}

/**
 * The key for one step of an attempt that already has one.
 *
 * A checkout is one attempt with several steps — hold the stock, then pay — and each step is
 * idempotent on its own. Deriving each step's key from the attempt's means a double-clicked Pay button
 * sends the same key twice and makes one sale.
 *
 * @param attemptKey the attempt's key
 * @param step which step
 * @returns the step's key
 */
export function stepKey(attemptKey: string, step: string): string {
  return `${attemptKey}.${step}`
}
