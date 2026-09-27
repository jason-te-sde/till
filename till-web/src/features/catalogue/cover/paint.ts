/**
 * The small toolkit the cover scenes are painted with: a seeded random source, colours, and paths.
 *
 * Seeded because a cover is part of a game's identity. The same game must look the same on the home
 * page, the search results, the order history and in a test, render after render — `Math.random` would
 * repaint every game on every visit.
 */

/** A random source: each call returns the next number in [0, 1). */
export type Random = () => number

/**
 * @param text what to seed from — a game's SKU
 * @returns a random source that yields the same sequence for the same text, always
 */
export function seeded(text: string): Random {
  // FNV-1a for the seed, mulberry32 for the sequence: both tiny, both good enough for art.
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  let state = hash >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** @returns a number in [low, high) */
export function between(random: Random, low: number, high: number): number {
  return low + random() * (high - low)
}

/** @returns one of the items */
export function pick<T>(random: Random, items: readonly T[]): T {
  const item = items[Math.floor(random() * items.length)]
  if (item === undefined) {
    throw new Error('pick from an empty list')
  }
  return item
}

/** @returns [0, 1, …, count - 1] */
export function range(count: number): number[] {
  return Array.from({ length: count }, (_, i) => i)
}

/**
 * @param hue in degrees, any value — it is wrapped
 * @param saturation percent
 * @param lightness percent
 * @param alpha 0 to 1
 * @returns an `hsla()` colour
 */
export function hsl(hue: number, saturation: number, lightness: number, alpha = 1): string {
  const h = ((Math.round(hue) % 360) + 360) % 360
  return `hsla(${String(h)}, ${String(Math.round(saturation))}%, ${String(Math.round(lightness))}%, ${String(alpha)})`
}

/** One decimal place is invisible at any size a cover is drawn, and halves the markup. */
export function r1(value: number): number {
  return Math.round(value * 10) / 10
}

export type Point = readonly [number, number]

function at(points: readonly Point[], index: number): Point {
  const point = points[((index % points.length) + points.length) % points.length]
  if (point === undefined) {
    throw new Error('a path needs points')
  }
  return point
}

/**
 * A smooth closed outline through the points: Catmull-Rom, converted to the cubic Béziers SVG speaks.
 *
 * @param points the outline, in order
 * @returns an SVG path
 */
export function smoothClosed(points: readonly Point[]): string {
  const [x0, y0] = at(points, 0)
  let path = `M${String(r1(x0))} ${String(r1(y0))}`
  for (let i = 0; i < points.length; i++) {
    const [ax, ay] = at(points, i - 1)
    const [bx, by] = at(points, i)
    const [cx, cy] = at(points, i + 1)
    const [dx, dy] = at(points, i + 2)
    const c1x = bx + (cx - ax) / 6
    const c1y = by + (cy - ay) / 6
    const c2x = cx - (dx - bx) / 6
    const c2y = cy - (dy - by) / 6
    path += ` C${String(r1(c1x))} ${String(r1(c1y))} ${String(r1(c2x))} ${String(r1(c2y))} ${String(r1(cx))} ${String(r1(cy))}`
  }
  return `${path}Z`
}

/**
 * An irregular round shape — an island, a cloud of contour — as points around a centre.
 *
 * @param random the source of irregularity
 * @param cx centre
 * @param cy centre
 * @param radius average radius
 * @param roughness how far each point may stray, as a fraction of the radius
 * @param count how many points
 * @returns the outline
 */
export function blob(random: Random, cx: number, cy: number, radius: number, roughness = 0.3, count = 12): Point[] {
  return range(count).map((i) => {
    const angle = (i / count) * Math.PI * 2
    const distance = radius * (1 - roughness / 2 + random() * roughness)
    return [cx + Math.cos(angle) * distance, cy + Math.sin(angle) * distance * 0.85] as const
  })
}

/**
 * A rolling horizon across the whole width, closed down to the bottom edge: hills, waves, a skyline's
 * ground.
 *
 * @param base the line's average height
 * @param amplitude how far it rises and falls
 * @param frequency how many rises across the width, roughly
 * @param phase where along the swell it starts
 * @returns an SVG path
 */
export function horizon(base: number, amplitude: number, frequency: number, phase: number): string {
  let path = `M0 400 L0 ${String(r1(base + amplitude * Math.sin(phase)))}`
  for (let x = 10; x <= 400; x += 10) {
    const t = (x / 400) * Math.PI * 2 * frequency + phase
    const y = base + amplitude * Math.sin(t) + amplitude * 0.35 * Math.sin(t * 2.7 + phase * 1.3)
    path += ` L${String(x)} ${String(r1(y))}`
  }
  return `${path} L400 400Z`
}

/**
 * A polygon's points attribute.
 *
 * @param points the corners
 * @returns `x,y x,y …`
 */
export function polygon(points: readonly Point[]): string {
  return points.map(([x, y]) => `${String(r1(x))},${String(r1(y))}`).join(' ')
}
