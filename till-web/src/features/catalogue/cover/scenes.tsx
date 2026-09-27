import type { ReactElement } from 'react'
import { between, blob, horizon, hsl, pick, polygon, r1, range, smoothClosed, type Point, type Random } from './paint'

/**
 * The twelve cover motifs, as scenes painted on a 400 × 400 canvas.
 *
 * Every game in the catalogue names one motif; its SKU seeds the details — hue, layout, how many stars
 * — so two games sharing a motif are recognisably related and never identical. Nothing here is an
 * image file: the store ships no artwork it does not own, and a vector scene is a few kilobytes that
 * stays sharp from a thumbnail to a hero banner.
 *
 * The canvas is square and drawn with `slice`, so a portrait card and a wide banner both crop it
 * around the centre. What matters in each scene is kept inside the middle — x 60–340, y 110–290 —
 * which is what survives either crop.
 */
export interface SceneProps {
  readonly random: Random
  /** Namespaces an SVG id, so two covers on one page never share a gradient by accident. */
  readonly id: (name: string) => string
  readonly hue: number
}

type Scene = (props: SceneProps) => ReactElement

const url = (id: string) => `url(#${id})`

/**
 * A starfield. A function rather than a component, deliberately: it draws from the scene's random
 * source, and a child component renders after its parent has finished — in development, twice — so
 * its draws would land in a different place in the sequence from one render to the next.
 */
function stars(random: Random, count: number, top = 400): ReactElement {
  return (
    <g>
      {range(count).map((i) => (
        <circle
          key={i}
          cx={r1(random() * 400)}
          cy={r1(random() * top)}
          r={r1(between(random, 0.3, 1.5))}
          fill="#fff"
          opacity={r1(between(random, 0.25, 1))}
        />
      ))}
    </g>
  )
}

function vignette(id: (name: string) => string, strength = 0.55): ReactElement {
  return (
    <>
      <defs>
        <radialGradient id={id('vignette')} cx="0.5" cy="0.5" r="0.75">
          <stop offset="0.55" stopColor="#000" stopOpacity="0" />
          <stop offset="1" stopColor="#000" stopOpacity={strength} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('vignette'))} />
    </>
  )
}

// --- space -----------------------------------------------------------------------------------------

const orbit: Scene = ({ random, id, hue }) => {
  const planetHue = hue + 150 + between(random, -40, 40)
  const cx = 200 + between(random, -30, 30)
  const cy = 205 + between(random, -18, 18)
  const r = between(random, 70, 96)
  const tilt = between(random, -28, -8)
  const moon: Point = [between(random, 90, 150), between(random, 110, 150)]
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0.3" y2="1">
          <stop offset="0" stopColor={hsl(hue, 55, 6)} />
          <stop offset="1" stopColor={hsl(hue + 25, 50, 17)} />
        </linearGradient>
        <radialGradient id={id('nebula')}>
          <stop offset="0" stopColor={hsl(hue + 45, 90, 58, 0.5)} />
          <stop offset="1" stopColor={hsl(hue + 45, 90, 58, 0)} />
        </radialGradient>
        <radialGradient id={id('nebula2')}>
          <stop offset="0" stopColor={hsl(hue - 40, 85, 55, 0.35)} />
          <stop offset="1" stopColor={hsl(hue - 40, 85, 55, 0)} />
        </radialGradient>
        <radialGradient id={id('planet')} cx="0.32" cy="0.28" r="0.85">
          <stop offset="0" stopColor={hsl(planetHue, 70, 70)} />
          <stop offset="0.5" stopColor={hsl(planetHue, 55, 44)} />
          <stop offset="1" stopColor={hsl(planetHue + 20, 60, 12)} />
        </radialGradient>
        <linearGradient id={id('shade')} x1="0.15" y1="0.1" x2="0.85" y2="0.95">
          <stop offset="0.4" stopColor={hsl(hue, 60, 4, 0)} />
          <stop offset="1" stopColor={hsl(hue, 60, 4, 0.8)} />
        </linearGradient>
        <linearGradient id={id('ring')} x1="0" x2="1">
          <stop offset="0" stopColor={hsl(planetHue - 30, 60, 78, 0.05)} />
          <stop offset="0.5" stopColor={hsl(planetHue - 30, 70, 82, 0.95)} />
          <stop offset="1" stopColor={hsl(planetHue - 30, 60, 78, 0.05)} />
        </linearGradient>
        <clipPath id={id('front')}>
          <rect x="-200" y={cy} width="800" height="400" transform={`rotate(${String(r1(tilt))} ${String(r1(cx))} ${String(r1(cy))})`} />
        </clipPath>
        <clipPath id={id('ball')}>
          <circle cx={cx} cy={cy} r={r} />
        </clipPath>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      <circle cx={r1(between(random, 60, 340))} cy={r1(between(random, 60, 160))} r="190" fill={url(id('nebula'))} />
      <circle cx={r1(between(random, 60, 340))} cy={r1(between(random, 240, 360))} r="160" fill={url(id('nebula2'))} />
      {stars(random, 90)}
      <ellipse
        cx={cx}
        cy={cy}
        rx={r * 1.95}
        ry={r * 0.42}
        fill="none"
        stroke={url(id('ring'))}
        strokeWidth={r * 0.17}
        opacity="0.5"
        transform={`rotate(${String(r1(tilt))} ${String(r1(cx))} ${String(r1(cy))})`}
      />
      <circle cx={cx} cy={cy} r={r} fill={url(id('planet'))} />
      <g clipPath={url(id('ball'))} opacity="0.3">
        {range(5).map((i) => (
          <ellipse
            key={i}
            cx={cx}
            cy={r1(cy - r * 0.62 + i * r * 0.3 + between(random, -6, 6))}
            rx={r * 1.3}
            ry={r1(r * between(random, 0.05, 0.12))}
            fill={hsl(planetHue + between(random, -15, 15), 50, between(random, 25, 70))}
          />
        ))}
      </g>
      <circle cx={cx} cy={cy} r={r} fill={url(id('shade'))} />
      <circle cx={cx} cy={cy} r={r + 1.5} fill="none" stroke={hsl(planetHue, 80, 75, 0.22)} strokeWidth="3" />
      <g clipPath={url(id('front'))}>
        <ellipse
          cx={cx}
          cy={cy}
          rx={r * 1.95}
          ry={r * 0.42}
          fill="none"
          stroke={url(id('ring'))}
          strokeWidth={r * 0.17}
          transform={`rotate(${String(r1(tilt))} ${String(r1(cx))} ${String(r1(cy))})`}
        />
      </g>
      <circle cx={moon[0]} cy={moon[1]} r="11" fill={hsl(hue + 200, 12, 82)} />
      <circle cx={moon[0] + 4} cy={moon[1] + 3} r="11" fill={hsl(hue, 50, 8, 0.55)} />
      {vignette(id, 0.45)}
    </>
  )
}

// --- cartography -----------------------------------------------------------------------------------

const map: Scene = ({ random, id, hue }) => {
  const paper = hue
  const islands = [
    { cx: between(random, 120, 180), cy: between(random, 150, 200), r: between(random, 60, 85) },
    { cx: between(random, 250, 300), cy: between(random, 230, 280), r: between(random, 45, 70) },
  ]
  const outlines = islands.map((island) => blob(random, island.cx, island.cy, island.r, 0.45, 14))
  const route: Point[] = [
    [between(random, 70, 110), between(random, 300, 340)],
    [islands[0]?.cx ?? 150, (islands[0]?.cy ?? 170) + 10],
    [between(random, 200, 230), between(random, 170, 210)],
    [islands[1]?.cx ?? 270, (islands[1]?.cy ?? 250) - 5],
  ]
  const [end] = route.slice(-1)
  const routePath = route
    .map(([x, y], i) => (i === 0 ? `M${String(r1(x))} ${String(r1(y))}` : `Q${String(r1((x + (route[i - 1]?.[0] ?? x)) / 2 + 30))} ${String(r1((y + (route[i - 1]?.[1] ?? y)) / 2 - 30))} ${String(r1(x))} ${String(r1(y))}`))
    .join(' ')
  return (
    <>
      <defs>
        <linearGradient id={id('sea')} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={hsl(190, 32, 62)} />
          <stop offset="1" stopColor={hsl(200, 36, 44)} />
        </linearGradient>
        <linearGradient id={id('land')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(paper, 48, 82)} />
          <stop offset="1" stopColor={hsl(paper - 6, 42, 66)} />
        </linearGradient>
        <radialGradient id={id('burn')} cx="0.5" cy="0.5" r="0.72">
          <stop offset="0.6" stopColor={hsl(paper - 10, 60, 20, 0)} />
          <stop offset="1" stopColor={hsl(paper - 10, 60, 16, 0.7)} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sea'))} />
      {range(9).map((i) => (
        <g key={i} stroke={hsl(200, 40, 85, 0.18)} strokeWidth="1">
          <line x1={i * 50} y1="0" x2={i * 50} y2="400" />
          <line x1="0" y1={i * 50} x2="400" y2={i * 50} />
        </g>
      ))}
      {outlines.map((outline, i) => (
        <g key={i}>
          <path d={smoothClosed(outline)} fill={hsl(200, 30, 30, 0.25)} transform="translate(4 6)" />
          <path d={smoothClosed(outline)} fill={url(id('land'))} stroke={hsl(paper - 15, 40, 32, 0.6)} strokeWidth="1.5" />
          {[0.72, 0.5, 0.3].map((scale) => {
            const island = islands[i]
            if (island === undefined) {
              return null
            }
            return (
              <path
                key={scale}
                d={smoothClosed(outline.map(([x, y]) => [island.cx + (x - island.cx) * scale, island.cy + (y - island.cy) * scale] as const))}
                fill="none"
                stroke={hsl(paper - 15, 40, 35, 0.4)}
                strokeWidth="1.2"
              />
            )
          })}
        </g>
      ))}
      <path d={routePath} fill="none" stroke={hsl(2, 68, 42)} strokeWidth="3.2" strokeDasharray="9 7" strokeLinecap="round" />
      {end !== undefined && (
        <g stroke={hsl(2, 72, 38)} strokeWidth="4.5" strokeLinecap="round">
          <line x1={end[0] - 9} y1={end[1] - 9} x2={end[0] + 9} y2={end[1] + 9} />
          <line x1={end[0] + 9} y1={end[1] - 9} x2={end[0] - 9} y2={end[1] + 9} />
        </g>
      )}
      <g transform="translate(318 104)">
        <circle r="30" fill={hsl(paper, 45, 80, 0.85)} stroke={hsl(paper - 15, 40, 30)} strokeWidth="1.5" />
        <polygon points={polygon([[0, -26], [6, -6], [0, 0], [-6, -6]])} fill={hsl(2, 65, 42)} />
        <polygon points={polygon([[0, 26], [6, 6], [0, 0], [-6, 6]])} fill={hsl(paper - 15, 40, 30)} />
        <polygon points={polygon([[-26, 0], [-6, -6], [0, 0], [-6, 6]])} fill={hsl(paper - 15, 40, 30)} />
        <polygon points={polygon([[26, 0], [6, -6], [0, 0], [6, 6]])} fill={hsl(paper - 15, 40, 30)} />
      </g>
      <rect width="400" height="400" fill={url(id('burn'))} />
    </>
  )
}

// --- pastoral --------------------------------------------------------------------------------------

const field: Scene = ({ random, id, hue }) => {
  const dusk = random() < 0.4
  const sun: Point = [between(random, 130, 270), between(random, 140, 175)]
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={dusk ? hsl(262, 45, 30) : hsl(205, 70, 60)} />
          <stop offset="0.7" stopColor={dusk ? hsl(18, 85, 62) : hsl(45, 90, 82)} />
        </linearGradient>
        <radialGradient id={id('sun')}>
          <stop offset="0" stopColor={hsl(dusk ? 30 : 48, 100, 80, 0.9)} />
          <stop offset="1" stopColor={hsl(dusk ? 30 : 48, 100, 80, 0)} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      <circle cx={sun[0]} cy={sun[1]} r="120" fill={url(id('sun'))} />
      <circle cx={sun[0]} cy={sun[1]} r={r1(between(random, 24, 34))} fill={hsl(dusk ? 32 : 50, 100, dusk ? 68 : 80)} />
      {range(3).map((i) => {
        const x = between(random, 40, 360)
        const y = between(random, 70, 130)
        return (
          <g key={i} fill="#fff" opacity={r1(between(random, 0.35, 0.65))}>
            <ellipse cx={x} cy={y} rx="34" ry="11" />
            <ellipse cx={x + 18} cy={y - 7} rx="20" ry="10" />
            <ellipse cx={x - 16} cy={y - 4} rx="16" ry="8" />
          </g>
        )
      })}
      {range(4).map((i) => (
        <path
          key={i}
          d={horizon(215 + i * 40, between(random, 10, 22), between(random, 0.8, 1.8), random() * 6)}
          fill={hsl(hue + i * 6, 32 + i * 9, dusk ? 42 - i * 9 : 64 - i * 10)}
        />
      ))}
      {range(Math.floor(between(random, 5, 9))).map((i) => {
        const x = between(random, 30, 370)
        const y = between(random, 272, 300)
        const size = between(random, 10, 18)
        return (
          <g key={i} fill={hsl(hue + 10, 45, dusk ? 14 : 22)}>
            <rect x={x - 1.5} y={y} width="3" height={size * 0.8} />
            <circle cx={x} cy={y - size * 0.3} r={size * 0.7} />
          </g>
        )
      })}
      {range(3).map((i) => {
        const x = sun[0] + between(random, -70, 70)
        const y = sun[1] + between(random, -50, -15)
        return (
          <path
            key={i}
            d={`M${String(r1(x - 7))} ${String(r1(y))} Q${String(r1(x - 3))} ${String(r1(y - 5))} ${String(r1(x))} ${String(r1(y))} Q${String(r1(x + 3))} ${String(r1(y - 5))} ${String(r1(x + 7))} ${String(r1(y))}`}
            fill="none"
            stroke={hsl(hue, 30, 15, 0.7)}
            strokeWidth="1.6"
            strokeLinecap="round"
          />
        )
      })}
    </>
  )
}

// --- industry --------------------------------------------------------------------------------------

function gear(cx: number, cy: number, outer: number, inner: number, teeth: number, turn: number): Point[] {
  return range(teeth * 4).map((i) => {
    const angle = turn + (i / (teeth * 4)) * Math.PI * 2
    const radius = i % 4 === 0 || i % 4 === 1 ? outer : inner
    return [cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius] as const
  })
}

const rust: Scene = ({ random, id, hue }) => {
  const sun: Point = [between(random, 140, 260), between(random, 130, 165)]
  const far = range(12).map((i) => ({ x: i * 36 - 10, w: between(random, 26, 40), h: between(random, 50, 130) }))
  const near = range(8).map((i) => ({ x: i * 54 - 20, w: between(random, 40, 60), h: between(random, 40, 95) }))
  const stacks = range(3).map(() => ({ x: between(random, 40, 360), h: between(random, 130, 190) }))
  const gearAt: Point = [pick(random, [58, 342]), between(random, 110, 170)]
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(hue + 12, 55, 40)} />
          <stop offset="0.55" stopColor={hsl(hue, 65, 20)} />
          <stop offset="1" stopColor={hsl(hue - 6, 60, 8)} />
        </linearGradient>
        <radialGradient id={id('glow')}>
          <stop offset="0" stopColor={hsl(hue + 20, 95, 65, 0.7)} />
          <stop offset="1" stopColor={hsl(hue + 20, 95, 65, 0)} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      <circle cx={sun[0]} cy={sun[1]} r="170" fill={url(id('glow'))} />
      <circle cx={sun[0]} cy={sun[1]} r="52" fill={hsl(hue + 22, 92, 64, 0.9)} />
      <polygon
        points={polygon(gear(gearAt[0], gearAt[1], 66, 54, 12, random()))}
        fill="none"
        stroke={hsl(hue + 10, 50, 50, 0.45)}
        strokeWidth="5"
      />
      <circle cx={gearAt[0]} cy={gearAt[1]} r="20" fill="none" stroke={hsl(hue + 10, 50, 50, 0.45)} strokeWidth="5" />
      {range(3).map((i) => (
        <rect key={i} x="0" y={190 + i * 22} width="400" height="18" fill={hsl(hue + 15, 30, 70, 0.06)} />
      ))}
      {far.map((b, i) => (
        <rect key={i} x={r1(b.x)} y={r1(300 - b.h)} width={r1(b.w)} height={r1(b.h + 100)} fill={hsl(hue, 35, 17)} />
      ))}
      {stacks.map((stack, i) => (
        <g key={i}>
          {range(5).map((puff) => (
            <circle
              key={puff}
              cx={r1(stack.x + 6 + puff * 14)}
              cy={r1(330 - stack.h - 12 - puff * 12)}
              r={8 + puff * 5}
              fill={hsl(hue, 10, 60, 0.16)}
            />
          ))}
          <rect x={r1(stack.x)} y={r1(330 - stack.h)} width="13" height={r1(stack.h)} fill={hsl(hue, 30, 8)} />
          <rect x={r1(stack.x - 3)} y={r1(330 - stack.h)} width="19" height="6" fill={hsl(hue, 30, 8)} />
        </g>
      ))}
      {near.map((b, i) => (
        <g key={i}>
          <rect x={r1(b.x)} y={r1(330 - b.h)} width={r1(b.w)} height={r1(b.h + 70)} fill={hsl(hue, 30, 7)} />
          {range(6).map((w) =>
            random() < 0.45 ? (
              <rect
                key={w}
                x={r1(b.x + 6 + (w % 3) * 13)}
                y={r1(330 - b.h + 10 + Math.floor(w / 3) * 16)}
                width="5"
                height="7"
                fill={hsl(40, 95, 62, 0.9)}
              />
            ) : null,
          )}
        </g>
      ))}
      {range(70).map((i) => {
        const x = random() * 420
        const y = random() * 400
        return <line key={i} x1={r1(x)} y1={r1(y)} x2={r1(x - 6)} y2={r1(y + 16)} stroke={hsl(hue + 20, 40, 85, 0.13)} strokeWidth="1" />
      })}
      {vignette(id, 0.5)}
    </>
  )
}

// --- geometry --------------------------------------------------------------------------------------

const tessera: Scene = ({ random, id, hue }) => {
  const palette = [
    hsl(hue, 70, 52),
    hsl(hue + 35, 72, 60),
    hsl(hue + 180, 68, 58),
    hsl(hue + 210, 60, 46),
    hsl(hue + 15, 30, 82),
  ]
  const cell = 46
  const gap = 7
  const count = 7
  const start = 200 - (count * cell + (count - 1) * gap) / 2
  const turn = between(random, -16, 16)
  const gold = [Math.floor(random() * count), Math.floor(random() * count)] as const
  return (
    <>
      <defs>
        <radialGradient id={id('bg')} cx="0.5" cy="0.45" r="0.75">
          <stop offset="0" stopColor={hsl(hue, 35, 17)} />
          <stop offset="1" stopColor={hsl(hue, 40, 6)} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('bg'))} />
      <g transform={`rotate(${String(r1(turn))} 200 200)`}>
        {range(count * count).map((i) => {
          const col = i % count
          const row = Math.floor(i / count)
          const x = start + col * (cell + gap)
          const y = start + row * (cell + gap)
          const roll = random()
          if (col === gold[0] && row === gold[1]) {
            return (
              <g key={i}>
                <rect x={x + 5} y={y + 7} width={cell} height={cell} rx="7" fill="#000" opacity="0.4" />
                <rect x={x - 3} y={y - 5} width={cell} height={cell} rx="7" fill={hsl(45, 95, 60)} stroke={hsl(45, 100, 80)} strokeWidth="2" />
              </g>
            )
          }
          if (roll < 0.12) {
            return <rect key={i} x={x} y={y} width={cell} height={cell} rx="7" fill="none" stroke={hsl(hue, 30, 40, 0.5)} strokeDasharray="4 4" />
          }
          const colour = pick(random, palette)
          if (roll > 0.9) {
            return (
              <g key={i}>
                <rect x={x + 5} y={y + 7} width={cell} height={cell} rx="7" fill="#000" opacity="0.4" />
                <rect x={x - 3} y={y - 5} width={cell} height={cell} rx="7" fill={colour} stroke="#fff" strokeOpacity="0.35" strokeWidth="2" />
              </g>
            )
          }
          return <rect key={i} x={x} y={y} width={cell} height={cell} rx="7" fill={colour} opacity={r1(between(random, 0.75, 1))} />
        })}
      </g>
      {vignette(id, 0.6)}
    </>
  )
}

// --- sound -----------------------------------------------------------------------------------------

const audio: Scene = ({ random, id, hue }) => {
  const centre: Point = [200, between(random, 170, 195)]
  const frequency = between(random, 0.045, 0.075)
  const phase = random() * 6
  let wave = ''
  for (let x = 0; x <= 400; x += 5) {
    const envelope = Math.exp(-(((x - 200) / 120) ** 2))
    const y = centre[1] + Math.sin(x * frequency + phase) * 46 * envelope + Math.sin(x * frequency * 2.6) * 10 * envelope
    wave += `${x === 0 ? 'M' : 'L'}${String(x)} ${String(r1(y))} `
  }
  return (
    <>
      <defs>
        <linearGradient id={id('bg')} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={hsl(hue, 70, 9)} />
          <stop offset="1" stopColor={hsl(hue + 35, 65, 21)} />
        </linearGradient>
        <radialGradient id={id('core')}>
          <stop offset="0" stopColor={hsl(hue + 60, 95, 75, 0.9)} />
          <stop offset="1" stopColor={hsl(hue + 60, 95, 75, 0)} />
        </radialGradient>
        <linearGradient id={id('bar')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(hue + 70, 95, 68)} />
          <stop offset="1" stopColor={hsl(hue - 5, 90, 42)} />
        </linearGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('bg'))} />
      {range(8).map((i) => (
        <circle
          key={i}
          cx={centre[0]}
          cy={centre[1]}
          r={30 + i * 26}
          fill="none"
          stroke={hsl(hue + 50, 95, 66, r1(Math.max(0.05, 0.55 - i * 0.07)))}
          strokeWidth="2"
        />
      ))}
      <circle cx={centre[0]} cy={centre[1]} r="90" fill={url(id('core'))} />
      <path d={wave} fill="none" stroke={hsl(hue + 80, 95, 72, 0.3)} strokeWidth="10" strokeLinecap="round" />
      <path d={wave} fill="none" stroke={hsl(hue + 80, 95, 78)} strokeWidth="3" strokeLinecap="round" />
      {range(28).map((i) => {
        const height = 18 + random() * 100 * (1 - Math.abs(i - 13.5) / 20)
        return <rect key={i} x={6 + i * 14} y={r1(400 - height)} width="9" height={r1(height + 4)} rx="3" fill={url(id('bar'))} opacity="0.9" />
      })}
      {stars(random, 30, 260)}
    </>
  )
}

// --- night, warm light -----------------------------------------------------------------------------

function pine(x: number, base: number, height: number): string {
  const w = height * 0.36
  return polygon([
    [x, base - height],
    [x + w * 0.55, base - height * 0.55],
    [x + w * 0.3, base - height * 0.55],
    [x + w, base],
    [x - w, base],
    [x - w * 0.3, base - height * 0.55],
    [x - w * 0.55, base - height * 0.55],
  ])
}

const lantern: Scene = ({ random, id, hue }) => {
  const moon: Point = [between(random, 270, 330), between(random, 80, 110)]
  const light: Point = [200 + between(random, -25, 25), 262]
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(hue + 15, 55, 7)} />
          <stop offset="1" stopColor={hsl(hue, 45, 17)} />
        </linearGradient>
        <radialGradient id={id('moon')}>
          <stop offset="0" stopColor={hsl(50, 70, 90, 0.5)} />
          <stop offset="1" stopColor={hsl(50, 70, 90, 0)} />
        </radialGradient>
        <radialGradient id={id('warm')}>
          <stop offset="0" stopColor={hsl(38, 100, 62, 0.6)} />
          <stop offset="0.5" stopColor={hsl(32, 100, 55, 0.18)} />
          <stop offset="1" stopColor={hsl(32, 100, 55, 0)} />
        </radialGradient>
        <linearGradient id={id('glass')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(48, 100, 82)} />
          <stop offset="1" stopColor={hsl(34, 100, 58)} />
        </linearGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      {stars(random, 50, 220)}
      <circle cx={moon[0]} cy={moon[1]} r="70" fill={url(id('moon'))} />
      <circle cx={moon[0]} cy={moon[1]} r="20" fill={hsl(50, 55, 88)} />
      {range(13).map((i) => (
        <polygon key={i} points={pine(i * 34 - 8 + between(random, -8, 8), 290, between(random, 70, 125))} fill={hsl(hue, 35, 13)} />
      ))}
      <circle cx={light[0]} cy={light[1]} r="165" fill={url(id('warm'))} />
      <path d={horizon(300, 8, 1.2, random() * 6)} fill={hsl(hue, 40, 6)} />
      {range(4).map((i) => {
        const side = i < 2 ? between(random, 10, 70) : between(random, 330, 390)
        return <polygon key={i} points={pine(side, 360, between(random, 150, 210))} fill={hsl(hue, 40, 4)} />
      })}
      <g transform={`translate(${String(r1(light[0]))} ${String(light[1])})`}>
        <path d="M-10 -38 Q0 -52 10 -38" fill="none" stroke={hsl(30, 20, 12)} strokeWidth="3" />
        <polygon points="-15,-36 15,-36 11,-30 -11,-30" fill={hsl(30, 20, 12)} />
        <rect x="-11" y="-30" width="22" height="34" rx="4" fill={url(id('glass'))} />
        <line x1="0" y1="-30" x2="0" y2="4" stroke={hsl(30, 20, 12)} strokeWidth="2" />
        <rect x="-14" y="4" width="28" height="6" rx="2" fill={hsl(30, 20, 12)} />
      </g>
      {range(26).map((i) => {
        const x = between(random, 40, 360)
        const y = between(random, 180, 330)
        const size = between(random, 1, 2.2)
        return (
          <g key={i}>
            <circle cx={r1(x)} cy={r1(y)} r={r1(size * 4)} fill={hsl(62, 100, 70, 0.12)} />
            <circle cx={r1(x)} cy={r1(y)} r={r1(size)} fill={hsl(62, 100, 75)} />
          </g>
        )
      })}
      {vignette(id, 0.4)}
    </>
  )
}

// --- sea -------------------------------------------------------------------------------------------

const tides: Scene = ({ random, id, hue }) => {
  const night = random() < 0.5
  const orb: Point = [between(random, 150, 250), between(random, 125, 150)]
  const lighthouseX = pick(random, [74, 326])
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={night ? hsl(hue + 20, 55, 9) : hsl(hue, 62, 52)} />
          <stop offset="1" stopColor={night ? hsl(hue, 50, 30) : hsl(28, 85, 76)} />
        </linearGradient>
        <radialGradient id={id('orb')}>
          <stop offset="0" stopColor={night ? hsl(50, 60, 92, 0.55) : hsl(40, 100, 75, 0.7)} />
          <stop offset="1" stopColor={night ? hsl(50, 60, 92, 0) : hsl(40, 100, 75, 0)} />
        </radialGradient>
        <linearGradient id={id('beam')} x1="0" y1="0" x2={lighthouseX < 200 ? '1' : '0'} y2="0">
          <stop offset="0" stopColor={hsl(50, 100, 85, 0.45)} />
          <stop offset="1" stopColor={hsl(50, 100, 85, 0)} />
        </linearGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      {night && stars(random, 50, 190)}
      <circle cx={orb[0]} cy={orb[1]} r="90" fill={url(id('orb'))} />
      <circle cx={orb[0]} cy={orb[1]} r="26" fill={night ? hsl(50, 50, 90) : hsl(42, 100, 72)} />
      <polygon
        points={polygon(
          lighthouseX < 200
            ? [[lighthouseX + 4, 138], [400, 90], [400, 190]]
            : [[lighthouseX - 4, 138], [0, 90], [0, 190]],
        )}
        fill={url(id('beam'))}
      />
      {range(8).map((i) => (
        <path
          key={i}
          d={horizon(206 + i * 25, 3 + i * 1.6, between(random, 2, 4), random() * 6)}
          fill={hsl(hue + i * 2, 55, night ? 22 - i * 2 : 46 - i * 4)}
        />
      ))}
      {range(16).map((i) => (
        <rect
          key={i}
          x={r1(orb[0] - 16 + between(random, -8, 8))}
          y={210 + i * 8}
          width={r1(32 - i * 1.2 + between(random, -6, 6))}
          height="2.5"
          rx="1"
          fill={night ? hsl(50, 60, 90, r1(0.6 - i * 0.03)) : hsl(42, 100, 80, r1(0.7 - i * 0.035))}
        />
      ))}
      <path d={`M${String(lighthouseX - 40)} 230 Q${String(lighthouseX)} 185 ${String(lighthouseX + 40)} 230 Z`} fill={hsl(hue, 30, night ? 8 : 20)} />
      <polygon points={polygon([[lighthouseX - 11, 205], [lighthouseX + 11, 205], [lighthouseX + 7, 142], [lighthouseX - 7, 142]])} fill={hsl(0, 0, 94)} />
      {[160, 180].map((y) => (
        <rect key={y} x={lighthouseX - 10} y={y} width="20" height="8" fill={hsl(2, 70, 48)} />
      ))}
      <rect x={lighthouseX - 9} y="128" width="18" height="14" rx="2" fill={hsl(50, 100, 80)} />
      <polygon points={polygon([[lighthouseX - 11, 128], [lighthouseX + 11, 128], [lighthouseX, 116]])} fill={hsl(2, 60, 35)} />
    </>
  )
}

// --- regal -----------------------------------------------------------------------------------------

const crown: Scene = ({ random, id, hue }) => {
  const jewels = [hsl(150, 70, 42), hsl(350, 78, 50), hsl(215, 78, 56)]
  const spikes: Point[] = [[100, 172], [145, 206], [172, 146], [200, 192], [228, 146], [255, 206], [300, 172]]
  return (
    <>
      <defs>
        <radialGradient id={id('bg')} cx="0.5" cy="0.47" r="0.7">
          <stop offset="0" stopColor={hsl(hue, 62, 34)} />
          <stop offset="1" stopColor={hsl(hue - 10, 65, 7)} />
        </radialGradient>
        <linearGradient id={id('gold')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(48, 100, 70)} />
          <stop offset="0.6" stopColor={hsl(40, 92, 50)} />
          <stop offset="1" stopColor={hsl(32, 90, 34)} />
        </linearGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('bg'))} />
      {range(18).map((i) => {
        const a = (i / 18) * Math.PI * 2 + random() * 0.05
        const b = a + Math.PI / 36
        return (
          <polygon
            key={i}
            points={polygon([[200, 210], [200 + Math.cos(a) * 420, 210 + Math.sin(a) * 420], [200 + Math.cos(b) * 420, 210 + Math.sin(b) * 420]])}
            fill={hsl(45, 90, 70, i % 2 === 0 ? 0.07 : 0.03)}
          />
        )
      })}
      <g transform="translate(0 6)">
        <path
          d={`M110 262 L${spikes.map(([x, y]) => `${String(x)} ${String(y)}`).join(' L')} L290 262 Z`}
          fill={url(id('gold'))}
          stroke={hsl(38, 80, 24)}
          strokeWidth="2.5"
          strokeLinejoin="round"
        />
        <rect x="104" y="246" width="192" height="26" rx="5" fill={hsl(38, 85, 40)} stroke={hsl(38, 80, 24)} strokeWidth="2.5" />
        {range(5).map((i) => (
          <circle key={i} cx={124 + i * 38} cy="259" r="7" fill={jewels[i % jewels.length]} stroke={hsl(45, 100, 85)} strokeWidth="1.5" />
        ))}
        {spikes.filter((_, i) => i % 2 === 0).map(([x, y]) => (
          <circle key={x} cx={x} cy={y - 7} r="8" fill={hsl(48, 100, 72)} stroke={hsl(38, 80, 24)} strokeWidth="2" />
        ))}
      </g>
      {range(6).map((i) => {
        const x = between(random, 70, 330)
        const y = between(random, 90, 150)
        const s = between(random, 4, 9)
        return (
          <polygon
            key={i}
            points={polygon([[x, y - s], [x + s * 0.25, y - s * 0.25], [x + s, y], [x + s * 0.25, y + s * 0.25], [x, y + s], [x - s * 0.25, y + s * 0.25], [x - s, y], [x - s * 0.25, y - s * 0.25]])}
            fill={hsl(48, 100, 85, 0.85)}
          />
        )
      })}
      {vignette(id, 0.5)}
    </>
  )
}

// --- ice -------------------------------------------------------------------------------------------

function ridge(random: Random, base: number, low: number, high: number, step: [number, number]): Point[] {
  const points: Point[] = [[-20, base]]
  let x = -20
  while (x < 420) {
    x += between(random, step[0], step[1])
    points.push([x, base - between(random, low, high)])
  }
  return points
}

const frost: Scene = ({ random, id, hue }) => {
  const auroraHue = 150 + between(random, -15, 35)
  const layers = [
    { base: 260, low: 60, high: 150, colour: hsl(hue, 28, 42), snow: true },
    { base: 300, low: 40, high: 110, colour: hsl(hue, 34, 27), snow: true },
    { base: 350, low: 20, high: 80, colour: hsl(hue, 40, 14), snow: false },
  ]
  return (
    <>
      <defs>
        <linearGradient id={id('sky')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(hue + 15, 60, 9)} />
          <stop offset="1" stopColor={hsl(hue, 45, 32)} />
        </linearGradient>
        <linearGradient id={id('aurora')} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={hsl(auroraHue, 90, 62, 0)} />
          <stop offset="0.5" stopColor={hsl(auroraHue, 90, 62, 0.55)} />
          <stop offset="1" stopColor={hsl(auroraHue + 40, 90, 62, 0)} />
        </linearGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('sky'))} />
      {stars(random, 55, 230)}
      {range(3).map((i) => {
        const y = between(random, 70, 150)
        const amplitude = between(random, 14, 30)
        const phase = random() * 6
        let top = `M-10 ${String(r1(y))}`
        let bottom = ''
        for (let x = 0; x <= 410; x += 20) {
          top += ` L${String(x)} ${String(r1(y + Math.sin(x / 60 + phase) * amplitude))}`
          bottom = ` L${String(x)} ${String(r1(y + 50 + Math.sin(x / 60 + phase + 0.6) * amplitude))}` + bottom
        }
        return <path key={i} d={`${top}${bottom} Z`} fill={url(id('aurora'))} opacity={r1(between(random, 0.5, 0.9))} />
      })}
      {layers.map((layer, i) => {
        const peaks = ridge(random, layer.base, layer.low, layer.high, [45, 75])
        return (
          <g key={i}>
            <polygon points={polygon([...peaks, [420, 400], [-20, 400]])} fill={layer.colour} />
            {layer.snow &&
              peaks.slice(1, -1).map(([x, y], p) => {
                const depth = (layer.base - y) * 0.28
                return (
                  <polygon
                    key={p}
                    points={polygon([[x, y], [x + depth * 0.55, y + depth], [x + depth * 0.15, y + depth * 0.75], [x - depth * 0.2, y + depth * 1.05], [x - depth * 0.55, y + depth]])}
                    fill={hsl(hue - 10, 30, 95, 0.9)}
                  />
                )
              })}
          </g>
        )
      })}
      {range(60).map((i) => (
        <circle key={i} cx={r1(random() * 400)} cy={r1(random() * 400)} r={r1(between(random, 0.8, 2.3))} fill="#fff" opacity={r1(between(random, 0.45, 0.95))} />
      ))}
    </>
  )
}

// --- dungeon ---------------------------------------------------------------------------------------

const hex: Scene = ({ random, id, hue }) => {
  const size = 26
  const h = Math.sqrt(3) * size
  const centre = (q: number, r: number): Point => [q * size * 1.5, h * (r + q / 2)]
  const corners = ([cx, cy]: Point): string =>
    polygon(range(6).map((i) => [cx + Math.cos((i * Math.PI) / 3) * (size - 1.5), cy + Math.sin((i * Math.PI) / 3) * (size - 1.5)] as const))
  const cells: { q: number; r: number; at: Point }[] = []
  for (let q = -1; q <= 11; q++) {
    for (let r = -Math.ceil(q / 2) - 1; r <= 10 - Math.floor(q / 2); r++) {
      const at = centre(q, r)
      if (at[0] > -30 && at[0] < 430 && at[1] > -30 && at[1] < 430) {
        cells.push({ q, r, at })
      }
    }
  }
  // The run: a walk from the lower left toward the middle, one neighbour at a time.
  const steps: [number, number][] = [[1, 0], [1, -1], [0, -1], [-1, 0], [-1, 1], [0, 1]]
  const walk: Point[] = []
  let q = 2
  let r = 5
  for (let i = 0; i < 8; i++) {
    walk.push(centre(q, r))
    const toward = steps.filter(([dq, dr]) => {
      const [nx, ny] = centre(q + dq, r + dr)
      const [cx, cy] = centre(q, r)
      return Math.hypot(nx - 210, ny - 190) < Math.hypot(cx - 210, cy - 190) || random() < 0.2
    })
    const [dq, dr] = pick(random, toward.length > 0 ? toward : steps)
    q += dq
    r += dr
  }
  const goal = walk[walk.length - 1] ?? ([200, 200] as const)
  return (
    <>
      <defs>
        <radialGradient id={id('bg')} cx="0.5" cy="0.48" r="0.72">
          <stop offset="0" stopColor={hsl(hue, 45, 14)} />
          <stop offset="1" stopColor={hsl(hue, 50, 4)} />
        </radialGradient>
        <radialGradient id={id('goal')}>
          <stop offset="0" stopColor={hsl(45, 100, 65, 0.8)} />
          <stop offset="1" stopColor={hsl(45, 100, 65, 0)} />
        </radialGradient>
      </defs>
      <rect width="400" height="400" fill={url(id('bg'))} />
      {cells.map((cell, i) => (
        <polygon
          key={i}
          points={corners(cell.at)}
          fill={random() < 0.1 ? hsl(hue, 80, 50, r1(between(random, 0.12, 0.4))) : 'none'}
          stroke={hsl(hue, 45, 32, 0.5)}
          strokeWidth="1.2"
        />
      ))}
      {walk.map((at, i) => (
        <polygon key={i} points={corners(at)} fill={hsl(hue + 20, 85, 55, r1(0.25 + (i / walk.length) * 0.4))} stroke={hsl(hue + 20, 90, 70, 0.8)} strokeWidth="1.5" />
      ))}
      <circle cx={goal[0]} cy={goal[1]} r="70" fill={url(id('goal'))} />
      <polygon points={corners(goal)} fill={hsl(45, 95, 60)} stroke={hsl(45, 100, 85)} strokeWidth="2" />
      {vignette(id, 0.65)}
    </>
  )
}

// --- the card table --------------------------------------------------------------------------------

const cards: Scene = ({ random, id, hue }) => {
  const suits = [
    { glyph: '♠', colour: hsl(230, 20, 14) },
    { glyph: '♥', colour: hsl(355, 75, 46) },
    { glyph: '♦', colour: hsl(355, 75, 46) },
    { glyph: '♣', colour: hsl(230, 20, 14) },
  ]
  const ranks = ['A', 'K', 'Q', 'J', '10', '7']
  const count = random() < 0.5 ? 4 : 5
  const spread = 16
  return (
    <>
      <defs>
        <radialGradient id={id('felt')} cx="0.5" cy="0.55" r="0.75">
          <stop offset="0" stopColor={hsl(hue, 45, 31)} />
          <stop offset="1" stopColor={hsl(hue + 5, 50, 10)} />
        </radialGradient>
        <pattern id={id('back')} width="10" height="10" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="10" height="10" fill={hsl(hue + 180, 55, 36)} />
          <rect width="5" height="10" fill={hsl(hue + 180, 55, 42)} />
        </pattern>
      </defs>
      <rect width="400" height="400" fill={url(id('felt'))} />
      <ellipse cx="200" cy="215" rx="220" ry="150" fill="none" stroke={hsl(hue, 40, 60, 0.12)} strokeWidth="2" />
      {[[70, 300], [330, 290]].map(([x, y], s) => (
        <g key={s}>
          {range(Math.floor(between(random, 3, 6))).map((i) => (
            <g key={i}>
              <ellipse cx={x} cy={(y ?? 300) - i * 6} rx="22" ry="8" fill={pick(random, [hsl(2, 70, 45), hsl(210, 70, 45), hsl(45, 90, 55), hsl(0, 0, 92)])} />
              <ellipse cx={x} cy={(y ?? 300) - i * 6} rx="22" ry="8" fill="none" stroke="#fff" strokeOpacity="0.7" strokeDasharray="5 5" strokeWidth="2" />
            </g>
          ))}
        </g>
      ))}
      <g transform="rotate(-24 200 330)">
        <rect x="154" y="140" width="92" height="132" rx="10" fill={url(id('back'))} stroke="#fff" strokeWidth="4" />
      </g>
      {range(count).map((i) => {
        const angle = (i - (count - 1) / 2) * spread
        const suit = pick(random, suits)
        const rank = pick(random, ranks)
        return (
          <g key={i} transform={`rotate(${String(r1(angle))} 200 340)`}>
            <rect x="154" y="136" width="92" height="132" rx="10" fill={hsl(40, 30, 97)} stroke={hsl(40, 10, 70)} strokeWidth="1.5" />
            <text x="164" y="160" fontSize="18" fontWeight="700" fill={suit.colour} fontFamily="Georgia, serif">
              {rank}
            </text>
            <text x="200" y="218" fontSize="46" textAnchor="middle" fill={suit.colour}>
              {suit.glyph}
            </text>
          </g>
        )
      })}
      {vignette(id, 0.55)}
    </>
  )
}

/** Each motif and the hue its palette is built around. */
export const SCENES: Readonly<Record<string, { scene: Scene; hue: number }>> = {
  orbit: { scene: orbit, hue: 245 },
  map: { scene: map, hue: 38 },
  field: { scene: field, hue: 118 },
  rust: { scene: rust, hue: 18 },
  tessera: { scene: tessera, hue: 190 },
  audio: { scene: audio, hue: 290 },
  lantern: { scene: lantern, hue: 205 },
  tides: { scene: tides, hue: 200 },
  crown: { scene: crown, hue: 345 },
  frost: { scene: frost, hue: 208 },
  hex: { scene: hex, hue: 160 },
  cards: { scene: cards, hue: 148 },
}
