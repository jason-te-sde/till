import { useEffect, useState } from 'react'

type Theme = 'system' | 'light' | 'dark'
const STORAGE_KEY = 'till.theme'

/**
 * Light, dark, or whatever the machine says.
 *
 * Three states rather than two. A two-state toggle has to pick a starting side, which means either
 * ignoring the operating system's setting or making "follow the system" unreachable once somebody
 * has touched it.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(() => read())

  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') {
      root.removeAttribute('data-theme')
      localStorage.removeItem(STORAGE_KEY)
    } else {
      root.setAttribute('data-theme', theme)
      localStorage.setItem(STORAGE_KEY, theme)
    }
  }, [theme])

  return (
    <label className="flex items-center gap-1.5 text-sm">
      <span className="sr-only">Theme</span>
      <select
        value={theme}
        onChange={(event) => {
          setTheme(event.target.value as Theme)
        }}
        className="rounded-md border border-line-strong bg-surface px-2 py-1.5 text-sm"
        aria-label="Theme"
      >
        <option value="system">System</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </label>
  )
}

function read(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' ? stored : 'system'
}
