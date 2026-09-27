import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  readonly children: ReactNode
}

interface State {
  readonly error: Error | undefined
}

/**
 * Catches a render that threw, so one broken page does not blank the store.
 *
 * Without this, React unmounts the whole tree when any component throws during render: the customer
 * loses the header, their cart button and the way home, and the only clue is in a console they are not
 * looking at. Placed inside the layout, so all of that stays on screen around the message.
 *
 * A class because that is the only thing React offers — `componentDidCatch` has no hook equivalent.
 * Deliberately not a retry that silently re-renders: whatever threw will usually throw again on the
 * same data, and a button that appears to do nothing is worse than one that reloads.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: undefined }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    // The component stack is the useful half, and React does not put it in the error.
    console.error('a page failed to render', error, info.componentStack)
  }

  override render() {
    const { error } = this.state
    if (error === undefined) {
      return this.props.children
    }
    return (
      <div role="alert" className="mx-auto my-16 max-w-xl rounded-2xl border border-line-strong bg-surface p-8 text-center">
        <h1 className="text-xl font-semibold">This page stopped working</h1>
        <p className="mt-2 text-ink-secondary">
          Something on this page failed to display. Your cart and your orders are safe — reloading usually
          fixes it.
        </p>
        <p className="mt-3 font-mono text-xs text-ink-muted">{error.message}</p>
        <button
          type="button"
          onClick={() => {
            window.location.reload()
          }}
          className="mt-5 inline-flex h-10 items-center rounded-lg bg-accent px-4 text-sm font-semibold text-accent-ink hover:bg-accent-hover"
        >
          Reload
        </button>
      </div>
    )
  }
}
