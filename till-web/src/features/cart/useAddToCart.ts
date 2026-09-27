import { useCallback } from 'react'
import type { GameCard } from '../../api/types'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { toastShown } from '../../app/uiSlice'
import { MAX_LINES, MAX_QUANTITY, added, selectCartLines } from './cartSlice'

/**
 * Adding a game to the cart, with the feedback that goes with it.
 *
 * One hook rather than a dispatch at each button, so every "Add to cart" in the store — on a card, on
 * a game's page, in the hero — confirms the same way and refuses the same way when the cart is full.
 *
 * @returns a function taking the game and how many copies
 */
export function useAddToCart() {
  const dispatch = useAppDispatch()
  const lines = useAppSelector(selectCartLines)

  return useCallback(
    (game: GameCard, quantity = 1) => {
      const existing = lines.find((line) => line.sku === game.sku)
      if (existing === undefined && lines.length >= MAX_LINES) {
        dispatch(
          toastShown({
            tone: 'error',
            title: 'Your cart is full',
            message: `An order can hold up to ${String(MAX_LINES)} different games.`,
          }),
        )
        return
      }
      if (existing !== undefined && existing.quantity >= MAX_QUANTITY) {
        dispatch(
          toastShown({
            tone: 'info',
            title: `${game.title} is already at the limit`,
            message: `Up to ${String(MAX_QUANTITY)} copies of a game per order.`,
          }),
        )
        return
      }
      dispatch(added({ game, quantity }))
      dispatch(
        toastShown({
          tone: 'success',
          title: 'Added to your cart',
          message: quantity > 1 ? `${String(quantity)} × ${game.title}` : game.title,
        }),
      )
    },
    [dispatch, lines],
  )
}
