import { TillError, TillUnreachableError } from '../api/client'

/**
 * What went wrong, in the words of whatever went wrong.
 *
 * A refusal for stock gets its shortfalls listed, because that is the one error a customer can act
 * on: "five wanted, two left" tells them to buy two. Everything else gets the service's own sentence,
 * which is written to be readable — see the rejection details in the kernel.
 */
export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (error === undefined || error === null) {
    return null
  }

  const isRefusal = error instanceof TillError
  const unreachable = error instanceof TillUnreachableError

  return (
    <div
      role="alert"
      className="rounded-lg border border-hairline bg-sunken px-4 py-3 text-sm"
      style={{ borderLeft: `3px solid var(--status-${isRefusal ? 'warning' : 'critical'})` }}
    >
      <p className="font-medium text-ink">
        {unreachable
          ? 'The till service could not be reached'
          : isRefusal
            ? titleFor(error)
            : 'Something went wrong'}
      </p>
      <p className="mt-1 text-ink-secondary">{messageFor(error)}</p>

      {isRefusal && error.shortfalls.length > 0 && (
        <ul className="numeric mt-2 space-y-0.5 text-ink-secondary">
          {error.shortfalls.map((shortfall) => (
            <li key={shortfall.sku}>
              <span className="font-medium text-ink">{shortfall.sku}</span>: wanted{' '}
              {shortfall.requested}, {shortfall.available} left
            </li>
          ))}
        </ul>
      )}

      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium text-ink hover:bg-surface"
        >
          Try again
        </button>
      )}
    </div>
  )
}

function titleFor(error: TillError): string {
  switch (error.code) {
    case 'INSUFFICIENT_STOCK':
      return 'Not enough left'
    case 'RESERVATION_EXPIRED':
      return 'That hold ran out'
    case 'ALREADY_COMMITTED':
      return 'Already paid for'
    case 'ALREADY_RELEASED':
      return 'Already cancelled'
    case 'IDEMPOTENCY_KEY_REUSED':
      return 'That key was used for a different request'
    case 'UNKNOWN_SKU':
      return 'No such item'
    case 'CONTENTION':
      return 'Too busy just now'
    default:
      return 'Refused'
  }
}

function messageFor(error: unknown): string {
  if (error instanceof TillError) {
    return error.detail
  }
  if (error instanceof TillUnreachableError) {
    return 'The request never got an answer. It may or may not have been applied — retrying with the same idempotency key is what makes finding out safe.'
  }
  return error instanceof Error ? error.message : String(error)
}
