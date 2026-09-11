/**
 * The shop's catalogue, which lives here rather than in till.
 *
 * That is not laziness. till holds no opinion about what a SKU means — it never joins to a product
 * table, and the only thing it would gain from knowing about products is validation the caller can
 * do better. A name, a price and a picture are the shop's business; how many there are is the
 * ledger's. This file is the demonstration of that split.
 */
export interface Product {
  readonly sku: string
  readonly name: string
  readonly blurb: string
  readonly pence: number
  readonly emoji: string
}

export const CATALOGUE: readonly Product[] = [
  { sku: 'widget', name: 'Widget', blurb: 'The canonical one.', pence: 1299, emoji: '🔧' },
  { sku: 'gadget', name: 'Gadget', blurb: 'Like a widget, fewer moving parts.', pence: 2450, emoji: '🧰' },
  { sku: 'doohickey', name: 'Doohickey', blurb: 'Nobody is quite sure.', pence: 799, emoji: '🪛' },
  { sku: 'sprocket', name: 'Sprocket', blurb: 'Eleven teeth. Do not ask.', pence: 3199, emoji: '⚙️' },
  { sku: 'grommet', name: 'Grommet', blurb: 'Holds the other things together.', pence: 249, emoji: '🔘' },
  { sku: 'flange', name: 'Flange', blurb: 'Structurally important.', pence: 1875, emoji: '🔩' },
]

export const BY_SKU: ReadonlyMap<string, Product> = new Map(
  CATALOGUE.map((product) => [product.sku, product]),
)
