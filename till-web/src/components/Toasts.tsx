import { useEffect } from 'react'
import { useAppDispatch, useAppSelector } from '../app/hooks'
import { selectToasts, toastDismissed, type Toast } from '../app/uiSlice'
import { Icon } from './Icon'

/**
 * Short confirmations and failures that do not belong to one place on the page — "Added to cart",
 * "Signed out" — stacked in a corner, announced politely, and gone on their own.
 */
export function Toasts() {
  const toasts = useAppSelector(selectToasts)
  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4 sm:items-end sm:pr-6"
    >
      {toasts.map((toast) => (
        <ToastCard key={toast.id} toast={toast} />
      ))}
    </div>
  )
}

function ToastCard({ toast }: { toast: Toast }) {
  const dispatch = useAppDispatch()
  useEffect(() => {
    // Errors stay longer: they are the ones somebody might need to read twice.
    const timer = setTimeout(
      () => {
        dispatch(toastDismissed(toast.id))
      },
      toast.tone === 'error' ? 8000 : 4000,
    )
    return () => {
      clearTimeout(timer)
    }
  }, [dispatch, toast.id, toast.tone])

  const icon = toast.tone === 'success' ? 'check' : toast.tone === 'error' ? 'alert' : 'info'
  const tint =
    toast.tone === 'success' ? 'text-good-ink' : toast.tone === 'error' ? 'text-critical-ink' : 'text-accent'
  return (
    <div
      role={toast.tone === 'error' ? 'alert' : 'status'}
      className="pointer-events-auto flex w-full max-w-sm animate-rise items-start gap-3 rounded-xl border border-hairline bg-raised p-4 shadow-pop"
    >
      <Icon name={icon} className={`mt-0.5 size-5 shrink-0 ${tint}`} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-ink">{toast.title}</p>
        {toast.message !== undefined && <p className="mt-0.5 text-sm text-ink-secondary">{toast.message}</p>}
      </div>
      <button
        type="button"
        aria-label="Dismiss"
        onClick={() => {
          dispatch(toastDismissed(toast.id))
        }}
        className="rounded-md p-1 text-ink-muted hover:bg-sunken hover:text-ink"
      >
        <Icon name="x" className="size-4" />
      </button>
    </div>
  )
}
