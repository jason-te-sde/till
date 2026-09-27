import { useEffect } from 'react'

const STORE = 'till games'

/**
 * Names the tab after the page, so a customer with six tabs open can find the one with their cart —
 * and a screen reader announces where a navigation landed.
 *
 * @param title the page's own name, or undefined while it is still loading
 */
export function useTitle(title: string | undefined): void {
  useEffect(() => {
    document.title = title === undefined ? STORE : `${title} · ${STORE}`
  }, [title])
}
