import { Link } from 'react-router'
import type { Facet } from '../../api/types'
import { plural } from '../../format'
import { GameCover } from './GameCover'

/** Which motif stands for each genre on its tile. Unlisted genres still get a tile, painted as space. */
const MOTIFS: Readonly<Record<string, string>> = {
  'Action RPG': 'crown',
  Strategy: 'map',
  Puzzle: 'tessera',
  Survival: 'frost',
  Narrative: 'lantern',
  Platformer: 'field',
  Simulation: 'audio',
  Roguelike: 'hex',
}

/** The genres as big tiles, each one a browse page already filtered to it. */
export function GenreGrid({ genres }: { genres: readonly Facet[] }) {
  return (
    <section aria-labelledby="genres-heading">
      <h2 id="genres-heading" className="text-xl font-bold tracking-tight sm:text-2xl">
        Browse by genre
      </h2>
      <ul className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {genres.map((genre) => (
          <li key={genre.value}>
            <Link
              to={`/browse?genre=${encodeURIComponent(genre.value)}`}
              className="group relative flex h-28 items-end overflow-hidden rounded-2xl p-4 shadow-card ring-1 ring-black/5 sm:h-32"
            >
              <GameCover
                sku={`genre-${genre.value}`}
                motif={MOTIFS[genre.value] ?? 'orbit'}
                className="absolute inset-0 transition duration-500 group-hover:scale-105"
              />
              <span aria-hidden="true" className="absolute inset-0 bg-gradient-to-t from-black/85 via-black/35 to-transparent" />
              <span className="relative">
                <span className="block text-base font-bold text-white sm:text-lg">{genre.value}</span>{' '}
                <span className="block text-xs text-white/70">{plural(genre.count, 'game')}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
