import { Icon } from './Icon'

/** − 2 + : a quantity, bounded, with buttons that say what they do to a screen reader. */
export function QuantityStepper({
  value,
  min = 1,
  max,
  onChange,
  label,
  size = 'md',
}: {
  value: number
  min?: number
  max: number
  onChange: (value: number) => void
  label: string
  size?: 'sm' | 'md'
}) {
  const box = size === 'sm' ? 'size-8' : 'size-10'
  return (
    <div className="inline-flex items-center rounded-lg border border-line-strong bg-surface" role="group" aria-label={label}>
      <button
        type="button"
        aria-label={`Fewer: ${label}`}
        disabled={value <= min}
        onClick={() => {
          onChange(value - 1)
        }}
        className={`grid ${box} place-items-center rounded-l-lg text-ink-secondary hover:bg-sunken hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent`}
      >
        <Icon name="minus" className="size-4" />
      </button>
      <output aria-live="polite" className={`numeric min-w-8 text-center font-semibold ${size === 'sm' ? 'text-sm' : ''}`}>
        {value}
      </output>
      <button
        type="button"
        aria-label={`More: ${label}`}
        disabled={value >= max}
        onClick={() => {
          onChange(value + 1)
        }}
        className={`grid ${box} place-items-center rounded-r-lg text-ink-secondary hover:bg-sunken hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent`}
      >
        <Icon name="plus" className="size-4" />
      </button>
    </div>
  )
}
