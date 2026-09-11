import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwind from '@tailwindcss/vite'

// In development the console runs on Vite's own server and the API is somewhere else, which would be
// a cross-origin request. Proxying rather than enabling CORS keeps development the same shape as
// production, where the service serves both from one origin and CORS never enters into it.
const api = process.env.TILL_API ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react(), tailwind()],
  server: {
    // Bound explicitly to IPv4. Vite resolves a default `localhost` to ::1 on this platform, and a
    // CI config or a health check written against 127.0.0.1 then cannot reach it — which is a
    // twenty-minute confusion the first time and every time.
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/v1': { target: api, changeOrigin: true },
      '/v3': { target: api, changeOrigin: true },
    },
  },
  // `vite preview` serves the built bundle, which is what the end-to-end suite runs against. It
  // needs the same proxy as the dev server, or every API call from the built app is cross-origin.
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: {
      '/v1': { target: api, changeOrigin: true },
      '/v3': { target: api, changeOrigin: true },
    },
  },
  build: {
    // Ends up inside the server jar under /static; see the `web` Maven profile.
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
      // Generated from openapi.json, and main.tsx is three lines of bootstrapping that only the
      // end-to-end suite can meaningfully execute.
      exclude: ['src/api/schema.d.ts', 'src/main.tsx'],
      reporter: ['text-summary', 'lcov'],
    },
  },
})
