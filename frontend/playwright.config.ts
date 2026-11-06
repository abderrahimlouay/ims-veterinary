import { defineConfig, devices } from '@playwright/test'
import { ADMIN } from './e2e/fixtures'

/**
 * End-to-end tests (testing.md §8) against the real backend: `spring-boot:test-run` starts it on a fresh
 * MySQL container (Docker and JDK 21 required) and bootstraps an administrator (ADR-040); the production
 * build is served by `vite preview`, which proxies `/api` to it.
 */
export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  // J1 bootstraps the administrator's own password; every other journey logs in with it, so they all run after J1.
  // Beyond that they are independent (testing.md §8): each creates its own products, lots, customers and suppliers,
  // looks only at them, and a journey that uses a cash session logs in as a user of its own (`e2e/users.ts`), so they
  // run in any order and in parallel.
  projects: [
    { name: 'j1', testMatch: /j1-.*\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium', testIgnore: /j1-.*\.spec\.ts/, dependencies: ['j1'], use: { ...devices['Desktop Chrome'] } },
    // ADR-159: Edge (current) runs the screen, header and accessibility journeys too; only where it is installed (CI).
    ...(process.env.E2E_EDGE
      ? [{ name: 'edge', testMatch: /(j13|j14)-.*\.spec\.ts/, dependencies: ['j1'], use: { ...devices['Desktop Edge'], channel: 'msedge' } }]
      : []),
  ],
  webServer: [
    {
      command: './mvnw -q spring-boot:test-run',
      cwd: '../backend',
      url: 'http://localhost:8080/actuator/health',
      timeout: 300_000,
      reuseExistingServer: !process.env.CI,
      env: {
        IMS_BOOTSTRAP_ADMIN_USERNAME: ADMIN.username,
        IMS_BOOTSTRAP_ADMIN_PASSWORD: ADMIN.bootstrapPassword,
        IMS_BOOTSTRAP_ADMIN_FULL_NAME: 'E2E Administrator',
      },
    },
    {
      command: 'npm run build && npm run preview -- --port 4173 --strictPort',
      url: 'http://localhost:4173',
      reuseExistingServer: !process.env.CI,
    },
  ],
})
