import { useEffect, useState } from 'react'
import { Icon, type IconName } from './Icon'

type Theme = 'system' | 'light' | 'dark'
const STORAGE_KEY = 'till.theme'

const OPTIONS: { value: Theme; label: string; icon: IconName }[] = [
  { value: 'system', label: 'System', icon: 'monitor' },
  { value: 'light', label: 'Light', icon: 'sun' },
  { value: 'dark', label: 'Dark', icon: 'moon' },
]

/**
 * Light, dark, or whatever the machine says.
 *
 * Three states rather than two. A two-state toggle has to pick a starting side, which means either
 * ignoring the operating system's setting or making "follow the system" unreachable once somebody has
 * touched it. The first paint's theme is applied by a script in `index.html`, before React loads, so a
 * dark page never flashes light.
 */
export function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(read)

  useEffect(() => {
    const root = document.documentElement
    try {
      if (theme === 'system') {
        root.removeAttribute('data-theme')
        localStorage.removeItem(STORAGE_KEY)
      } else {
        root.setAttribute('data-theme', theme)
        localStorage.setItem(STORAGE_KEY, theme)
      }
    } catch {
      // Storage denied: the choice lasts for this page, which is still a choice.
    }
  }, [theme])

  return (
    <div role="radiogroup" aria-label="Theme" className="inline-flex rounded-lg border border-hairline bg-sunken p-0.5">
      {OPTIONS.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={theme === option.value}
          aria-label={option.label}
          title={option.label}
          onClick={() => {
            setTheme(option.value)
          }}
          className={`grid size-7 place-items-center rounded-md transition ${
            theme === option.value ? 'bg-surface text-ink shadow-sm' : 'text-ink-muted hover:text-ink'
          }`}
        >
          <Icon name={option.icon} className="size-4" />
        </button>
      ))}
    </div>
  )
}

function read(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'system'
  } catch {
    return 'system'
  }
}
