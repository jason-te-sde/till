import { useId } from 'react'
import { between, seeded } from './cover/paint'
import { SCENES } from './cover/scenes'

/**
 * A game's cover art: its motif, painted with details seeded by its SKU.
 *
 * The caller decides where the cover sits and how big it is — `absolute inset-0` to fill a card,
 * `h-24 w-18` for a thumbnail, an aspect ratio for a game's page — and the outer element takes only
 * those classes. Its own positioning lives on an inner element, because two position utilities on one
 * element (`relative` here, `absolute` from the caller) resolve by stylesheet order, not by intent, and
 * the loser is a cover of zero height.
 *
 * Decorative, and marked so: every place a cover appears also names the game in text, and a screen
 * reader has no use for a description of a procedurally painted planet.
 */
export function GameCover({
  sku,
  motif,
  className = '',
}: {
  sku: string
  motif: string
  className?: string
}) {
  // useId's characters are not all valid in an SVG `url(#…)` reference, so it is reduced to ones that
  // are. Unique per instance, which matters: the same game on a page twice must not share gradients.
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const random = seeded(sku)
  const { scene, hue } = SCENES[motif] ?? SCENES['orbit'] ?? fallback()
  const content = scene({ random, id: (name) => `c${uid}-${name}`, hue: hue + between(random, -18, 18) })
  return (
    <div className={`overflow-hidden bg-sunken ${className}`}>
      <div className="grain relative size-full">
        <svg
          viewBox="0 0 400 400"
          preserveAspectRatio="xMidYMid slice"
          aria-hidden="true"
          focusable="false"
          className="absolute inset-0 size-full"
        >
          {content}
        </svg>
      </div>
    </div>
  )
}

function fallback(): never {
  throw new Error('no cover scenes are registered')
}
