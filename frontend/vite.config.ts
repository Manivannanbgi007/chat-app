import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

// Dev server runs on port 5210 and forwards /api and /ws to the API gateway (port 8200).
// The browser only ever talks to the gateway, never to a service directly.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5210,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8200',
      '/ws': { target: 'ws://localhost:8200', ws: true },
    },
  },
  // Unit tests: jsdom simulates a browser.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
    // e2e/ holds Playwright tests (run with `npm run e2e`), not Vitest tests
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
