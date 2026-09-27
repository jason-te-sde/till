import { useEffect, useRef, type ReactNode } from 'react'
import { Icon } from './Icon'

/**
 * A modal on the platform's own `<dialog>`: focus is trapped, Escape closes it, the page behind is
 * inert and a screen reader knows it is a dialog — all without a line of focus-management code that
 * could get any of it wrong.
 *
 * `side` turns it into a drawer from the right edge, which is how the cart opens.
 */
export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  side = false,
}: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  footer?: ReactNode
  side?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (dialog === null) {
      return
    }
    if (open && !dialog.open) {
      dialog.showModal()
    } else if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  const frame = side
    ? 'ml-auto mr-0 h-dvh max-h-dvh w-full max-w-md animate-slide-in rounded-none'
    : 'm-auto w-[calc(100%-2rem)] max-w-lg animate-rise rounded-2xl'

  return (
    <dialog
      ref={ref}
      aria-label={title}
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onClick={(event) => {
        // A click on the backdrop lands on the dialog element itself; a click inside lands on content.
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
      className={`${frame} border border-hairline bg-raised p-0 text-ink shadow-pop backdrop:bg-black/55 backdrop:backdrop-blur-[2px]`}
    >
      <div className={`flex flex-col ${side ? 'h-full' : 'max-h-[85dvh]'}`}>
        <header className="flex items-center justify-between border-b border-hairline px-5 py-4">
          <h2 className="text-lg font-semibold">{title}</h2>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-lg p-1.5 text-ink-muted hover:bg-sunken hover:text-ink"
          >
            <Icon name="x" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer !== undefined && <footer className="border-t border-hairline px-5 py-4">{footer}</footer>}
      </div>
    </dialog>
  )
}
