import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { Icon } from '../components/Icon'

/**
 * The header's search. Submitting goes to the browse page with the text in the URL, so a search is a
 * link like any other page. "/" focuses it from anywhere, as it does on most sites people search.
 */
export function SearchBox({ initial }: { initial: string }) {
  const [text, setText] = useState(initial)
  const input = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const typing = target !== null && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
      if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
        event.preventDefault()
        input.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [])

  return (
    <form
      role="search"
      onSubmit={(event) => {
        event.preventDefault()
        const query = text.trim()
        void navigate(query === '' ? '/browse' : `/browse?q=${encodeURIComponent(query)}`)
        input.current?.blur()
      }}
      className="relative w-full"
    >
      <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-ink-muted" />
      <input
        ref={input}
        type="search"
        value={text}
        maxLength={100}
        onChange={(event) => {
          setText(event.target.value)
        }}
        placeholder="Search games, studios, genres"
        aria-label="Search the store"
        className="h-10 w-full rounded-xl border border-hairline bg-sunken pr-10 pl-9 text-sm text-ink placeholder:text-ink-muted focus:border-accent focus:bg-surface focus:outline-none"
      />
      <kbd className="pointer-events-none absolute top-1/2 right-3 hidden -translate-y-1/2 rounded border border-hairline px-1.5 font-mono text-[10px] text-ink-muted sm:block">
        /
      </kbd>
    </form>
  )
}
