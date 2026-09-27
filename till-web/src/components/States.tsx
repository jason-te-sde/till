import type { ReactNode } from 'react'
import { isTransient, messageFor } from '../api/problem'
import type { Problem } from '../api/types'
import { button } from './button'
import { Icon, type IconName } from './Icon'

/**
 * The three states every page that loads something is in before it is in the fourth.
 *
 * Kept together and used everywhere so they are the same everywhere: a shimmering skeleton in the shape
 * of what is coming, never a spinner in an empty page and never "no results" before the results have
 * had a chance to arrive; an empty state that says what to do next; and an error that says what went
 * wrong in the customer's words, with a retry when retrying could help.
 */

export function Skeleton({ className = '' }: { className?: string }) {
  return <div aria-hidden="true" className={`skeleton rounded-lg ${className}`} />
}

export function EmptyState({
  icon = 'box',
  title,
  children,
  action,
}: {
  icon?: IconName
  title: string
  children?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center rounded-2xl border border-dashed border-line-strong px-6 py-14 text-center">
      <span className="grid size-12 place-items-center rounded-full bg-accent-soft text-accent-soft-ink">
        <Icon name={icon} className="size-6" />
      </span>
      <h2 className="mt-4 text-lg font-semibold">{title}</h2>
      {children !== undefined && <div className="mt-1.5 max-w-md text-sm text-ink-secondary">{children}</div>}
      {action !== undefined && <div className="mt-5">{action}</div>}
    </div>
  )
}

export function ErrorNotice({
  problem,
  onRetry,
  compact = false,
}: {
  problem: Problem
  onRetry?: () => void
  compact?: boolean
}) {
  const retry = onRetry !== undefined && isTransient(problem)
  return (
    <div
      role="alert"
      className={`flex items-start gap-3 rounded-xl border border-line-strong bg-surface ${compact ? 'p-3' : 'p-5'}`}
    >
      <Icon name="alert" className="mt-0.5 size-5 shrink-0 text-critical-ink" />
      <div className="min-w-0 flex-1">
        <p className="font-medium text-ink">{messageFor(problem)}</p>
        {!compact && problem.code !== 'NETWORK' && (
          <p className="mt-1 font-mono text-xs text-ink-muted">
            {problem.code}
            {problem.status > 0 ? ` · ${String(problem.status)}` : ''}
          </p>
        )}
      </div>
      {retry && (
        <button type="button" onClick={onRetry} className={button('secondary', 'sm')}>
          <Icon name="refresh" className="size-4" />
          Try again
        </button>
      )}
    </div>
  )
}
