/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { SECURITY_HEADERS } from './src/securityHeaders.ts'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // The SPA talks only to /api (architecture.md §2.1); the backend runs on 8080 in development.
    proxy: { '/api': 'http://localhost:8080' },
  },
  preview: {
    // ADR-157: the production build is checked under the proxy's headers (e2e).
    headers: SECURITY_HEADERS,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
