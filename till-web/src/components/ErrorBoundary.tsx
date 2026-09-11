import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  readonly children: ReactNode
}

interface State {
  readonly error: Error | undefined
}

/**
 * Catches a render that threw, so one broken panel does not blank the console.
 *
 * Without this, React 19 unmounts the whole tree when any component throws during render: the
 * operator loses the page, the navigation and the token prompt, and the only clue is in a console
 * they are not looking at. The service can be perfectly healthy while the window is white.
 *
 * It is a class because that is the only thing React gives us — `componentDidCatch` has no hook
 * equivalent, deliberately, and there is no library here that would paper over it.
 *
 * Deliberately **not** a retry button that silently re-renders. Whatever threw will usually throw
 * again on the same data, and a button that appears to do nothing is worse than one that reloads.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: undefined }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    // The component stack is the useful half and React does not put it in the error, so this is the
    // one place it can be recorded at all.
    console.error('a panel failed to render', error, info.componentStack)
  }

  override render() {
    const { error } = this.state
    if (error === undefined) {
      return this.props.children
    }
    return (
      <div
        role="alert"
        className="mx-auto max-w-2xl rounded-lg border border-line-strong bg-surface p-6"
      >
        <h1 className="text-lg font-semibold">This page stopped working</h1>
        <p className="mt-2 text-ink-secondary">
          Something in the console failed to render. The service itself may be fine — the ledger is
          not involved in drawing this page.
        </p>
        <p className="mt-3 font-mono text-sm text-ink-muted">{error.message}</p>
        <button
          type="button"
          onClick={() => {
            window.location.reload()
          }}
          className="mt-4 rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium hover:bg-sunken"
        >
          Reload
        </button>
      </div>
    )
  }
}
