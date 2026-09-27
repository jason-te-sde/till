import { createSlice, nanoid, type PayloadAction } from '@reduxjs/toolkit'

export type ToastTone = 'success' | 'info' | 'error'

export interface Toast {
  readonly id: string
  readonly tone: ToastTone
  readonly title: string
  readonly message?: string
}

export interface UiState {
  readonly cartOpen: boolean
  readonly toasts: readonly Toast[]
}

const initialState: UiState = { cartOpen: false, toasts: [] }

/** At most this many on screen; a burst of failures should not wallpaper the page. */
const MAX_TOASTS = 3

/**
 * The parts of the page that any component may open: the cart drawer and the toasts.
 *
 * In the store rather than in a context because the things that open them are far apart — a game
 * page's button opens the cart, a failed payment three components deep raises a toast — and neither
 * should have to know where the other is drawn.
 */
export const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    cartOpened(state) {
      state.cartOpen = true
    },
    cartClosed(state) {
      state.cartOpen = false
    },
    toastShown: {
      reducer(state, action: PayloadAction<Toast>) {
        state.toasts = [...state.toasts, action.payload].slice(-MAX_TOASTS)
      },
      prepare(toast: Omit<Toast, 'id'>) {
        return { payload: { ...toast, id: nanoid() } }
      },
    },
    toastDismissed(state, action: PayloadAction<string>) {
      state.toasts = state.toasts.filter((toast) => toast.id !== action.payload)
    },
  },
  selectors: {
    selectCartOpen: (ui) => ui.cartOpen,
    selectToasts: (ui) => ui.toasts,
  },
})

export const { cartOpened, cartClosed, toastShown, toastDismissed } = uiSlice.actions
export const { selectCartOpen, selectToasts } = uiSlice.selectors
