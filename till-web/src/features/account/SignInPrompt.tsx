import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { signInHref } from './session'

/**
 * What a page that needs an account shows to somebody without one: why, and one button — which brings
 * them straight back here afterwards.
 */
export function SignInPrompt({ title, children, returnTo }: { title: string; children: string; returnTo: string }) {
  return (
    <div className="mx-auto max-w-md rounded-2xl border border-hairline bg-surface p-8 text-center shadow-card">
      <span className="mx-auto grid size-12 place-items-center rounded-full bg-accent-soft text-accent-soft-ink">
        <Icon name="user" className="size-6" />
      </span>
      <h1 className="mt-4 text-xl font-bold">{title}</h1>
      <p className="mt-2 text-sm text-ink-secondary">{children}</p>
      <a href={signInHref(returnTo)} className={button('primary', 'lg', 'mt-6 w-full')}>
        Sign in
      </a>
      <p className="mt-3 text-xs text-ink-muted">You will come straight back here.</p>
    </div>
  )
}
