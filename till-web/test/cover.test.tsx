import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { seeded } from '../src/features/catalogue/cover/paint'
import { SCENES } from '../src/features/catalogue/cover/scenes'
import { GameCover } from '../src/features/catalogue/GameCover'

/** The markup, with the per-instance id prefix removed so two renders can be compared. */
function markup(sku: string, motif: string): string {
  return renderToStaticMarkup(<GameCover sku={sku} motif={motif} />).replace(/c[a-zA-Z0-9_]*-(?=[a-z0-9]+["')])/g, 'c-')
}

describe('cover art', () => {
  it('paints the same game the same way every time', () => {
    expect(markup('sunless-orbit', 'orbit')).toBe(markup('sunless-orbit', 'orbit'))
  })

  it('paints two games that share a motif differently', () => {
    expect(markup('sunless-orbit', 'orbit')).not.toBe(markup('deep-field', 'orbit'))
  })

  it('paints every motif without failing, and something for a motif it does not know', () => {
    for (const motif of [...Object.keys(SCENES), 'no-such-motif']) {
      const svg = markup(`game-${motif}`, motif)
      expect(svg).toContain('<svg')
      expect(svg).toContain('aria-hidden="true"')
    }
  })

  it('draws from a random source that is seeded, not random', () => {
    const a = seeded('tessera')
    const b = seeded('tessera')
    const draws = Array.from({ length: 5 }, () => [a(), b()])
    for (const [x, y] of draws) {
      expect(x).toBe(y)
      expect(x).toBeGreaterThanOrEqual(0)
      expect(x).toBeLessThan(1)
    }
    expect(seeded('canopy')()).not.toBe(seeded('tessera')())
  })
})
