/**
 * The button styles, as class strings — so a `<button>`, a router `<Link>` and a plain `<a>` to the
 * sign-in endpoint can all look like the same button without a component that tries to be all three.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'sale'
export type ButtonSize = 'sm' | 'md' | 'lg'

const BASE =
  'inline-flex items-center justify-center gap-2 rounded-lg font-semibold whitespace-nowrap transition ' +
  'duration-150 select-none disabled:cursor-not-allowed disabled:opacity-50 active:translate-y-px'

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-accent-ink shadow-sm hover:bg-accent-hover',
  secondary: 'border border-line-strong bg-surface text-ink hover:bg-sunken',
  ghost: 'text-ink-secondary hover:bg-sunken hover:text-ink',
  danger: 'border border-line-strong bg-surface text-critical-ink hover:bg-sunken',
  sale: 'bg-sale text-sale-ink shadow-sm hover:brightness-110',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
}

/**
 * @param variant how loud
 * @param size how big
 * @param extra more classes, for layout
 * @returns the class string
 */
export function button(variant: ButtonVariant = 'primary', size: ButtonSize = 'md', extra = ''): string {
  return `${BASE} ${VARIANTS[variant]} ${SIZES[size]} ${extra}`.trim()
}
