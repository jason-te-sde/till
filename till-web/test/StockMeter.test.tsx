import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StockLegend, StockMeter } from '../src/ops/StockMeter'
import { stock } from './fixtures'

describe('the stock meter', () => {
  it('describes itself for anyone not looking at the colours', () => {
    render(<StockMeter stock={stock('widget', 100, 30)} />)

    // The bar is a picture of the ratio; the numbers are what it means, and they are available to a
    // screen reader, to a print-out and to a colourblind reader without any of them.
    expect(
      screen.getByRole('img', {
        name: 'widget: 70 available, 30 reserved, 100 on hand',
      }),
    ).toBeInTheDocument()
  })

  it('splits the bar in proportion to the two halves of on-hand', () => {
    const { container } = render(<StockMeter stock={stock('widget', 100, 30)} />)
    const segments = container.querySelectorAll<HTMLElement>('[style*="width"]')

    expect(segments).toHaveLength(2)
    expect(segments[0]!.style.width).toBe('70%')
    expect(segments[1]!.style.width).toBe('30%')
  })

  it('draws one segment when everything is on one side', () => {
    const { container } = render(<StockMeter stock={stock('widget', 10, 10)} />)

    expect(container.querySelectorAll('[style*="width"]')).toHaveLength(1)
  })

  it('does not divide by zero on a SKU with nothing in it', () => {
    render(<StockMeter stock={stock('widget', 0, 0)} />)

    expect(
      screen.getByRole('img', { name: 'widget: 0 available, 0 reserved, 0 on hand' }),
    ).toBeInTheDocument()
  })

  it('names both series in the legend, so colour is never the only channel', () => {
    render(<StockLegend />)

    expect(screen.getByText('Available')).toBeInTheDocument()
    expect(screen.getByText('Reserved')).toBeInTheDocument()
  })
})
