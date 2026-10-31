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
  // J1 bootstraps the administrator's own password; the other journeys log in with it, so they run after J1.
  // J8 creates the first lots, so it runs after the journeys that expect none yet (J2's empty lot screens).
  // J12 (customers) adds a category, a product and a customer, after those journeys so none of them sees these.
  // J4 and J5 (sales) run last: J4 opens the cash session that J5's cash sale uses.
  projects: [
    { name: 'j1', testMatch: /j1-.*\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium', testIgnore: /(j1|j3|j4|j5|j8|j12)-.*\.spec\.ts/, dependencies: ['j1'], use: { ...devices['Desktop Chrome'] } },
    { name: 'stock', testMatch: /(j3|j8)-.*\.spec\.ts/, dependencies: ['chromium'], use: { ...devices['Desktop Chrome'] } },
    { name: 'customers', testMatch: /j12-.*\.spec\.ts/, dependencies: ['stock'], use: { ...devices['Desktop Chrome'] } },
    { name: 'sales', testMatch: /(j4|j5)-.*\.spec\.ts/, dependencies: ['customers'], use: { ...devices['Desktop Chrome'] } },
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
