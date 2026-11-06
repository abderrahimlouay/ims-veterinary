import { expect, test } from '@playwright/test'
import { SECURITY_HEADERS } from '../src/securityHeaders'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J13 (ADR-157, ADR-166): the production build runs under the Content-Security-Policy the application sends, served by `vite preview`
 * with the same headers: logging in and opening the main screens trigger no policy violation and no page error.
 * Runs after J1.
 */
const SCREENS = ['/', '/products', '/lots', '/stock', '/pos', '/sales', '/customers', '/reports', '/reports/revenue',
  '/reports/customerBalances', '/admin/settings', '/admin/audit']

test('J13: the main screens run under the production security headers', async ({ page }) => {
  const violations: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content[- ]Security[- ]Policy/i.test(message.text())) {
      violations.push(message.text())
    }
  })
  page.on('pageerror', (error) => violations.push(error.message))

  const response = await page.goto('/login')
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
    expect(response?.headers()[name.toLowerCase()], name).toBe(value)
  }
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page).not.toHaveURL(/\/login$/)

  for (const screen of SCREENS) {
    await page.goto(screen)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  }
  expect(violations).toEqual([])
})
