import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwind from '@tailwindcss/vite'

// In development the SPA runs on Vite's server and everything else is behind the stack's edge proxy.
// Proxying rather than enabling CORS keeps development the same shape as production: one origin, a
// session cookie that is first-party, and CSRF protection that works the way it will in a deployment.
//
// `xfwd` and the unchanged Host header are what make signing in work through the proxy: the store
// builds the OAuth redirect URI from them, so the identity provider sends the browser back here, to
// Vite, rather than to the edge's own address.
const edge = process.env.TILL_EDGE ?? 'http://127.0.0.1:8080'
const backend = Object.fromEntries(
  ['/api', '/oauth2', '/login/oauth2', '/v3', '/swagger-ui'].map((path) => [
    path,
    { target: edge, changeOrigin: false, xfwd: true },
  ]),
)

export default defineConfig({
  plugins: [react(), tailwind()],
  server: {
    // Bound explicitly to IPv4. Vite resolves a default `localhost` to ::1 on this platform, and a
    // health check written against 127.0.0.1 then cannot reach it.
    host: '127.0.0.1',
    port: 5173,
    proxy: backend,
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: backend,
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.ts'],
    include: ['test/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      // Generated from the contract; and main.tsx is bootstrapping only a browser can execute.
      exclude: ['src/api/schema.d.ts', 'src/main.tsx'],
      reporter: ['text-summary', 'lcov'],
    },
  },
})
