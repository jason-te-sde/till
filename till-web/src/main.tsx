import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Provider } from 'react-redux'
import { BrowserRouter } from 'react-router'
import { App } from './app/App'
import { makeStore } from './app/store'
import { replaced } from './features/cart/cartSlice'
import { CART_STORAGE_KEY, loadCart } from './features/cart/persistence'
import './index.css'

const root = document.getElementById('root')
if (root === null) {
  throw new Error('no #root in the document')
}

const store = makeStore()

// A cart changed in another tab shows up in this one, rather than being overwritten by it on the next
// click. The `storage` event fires only in the other tabs, never in the one that wrote.
window.addEventListener('storage', (event) => {
  if (event.key === CART_STORAGE_KEY) {
    store.dispatch(replaced(loadCart()))
  }
})

createRoot(root).render(
  <StrictMode>
    <Provider store={store}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </Provider>
  </StrictMode>,
)
