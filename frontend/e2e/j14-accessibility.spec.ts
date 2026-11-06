import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J14 (frontend.md §12, testing.md §8): axe-core finds no WCAG 2.1 A or AA violation on the main screens, in French
 * and, for the counter and the reports, in Arabic (right to left). axe is injected as a script, which the production
 * Content-Security-Policy would block (J13 checks the policy), hence `bypassCSP` here only. Runs after J1.
 */
test.use({ bypassCSP: true })

const AXE = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8')
const SCREENS = ['/', '/products', '/products/new', '/lots', '/stock', '/pos', '/sales', '/customers', '/suppliers',
  '/reports', '/reports/revenue', '/reports/customerBalances', '/admin/users', '/admin/roles', '/admin/settings',
  '/admin/audit', '/admin/backups']
const RTL_SCREENS = ['/pos', '/reports/revenue', '/admin/backups']

async function violations(page: Page): Promise<string[]> {
  await page.addScriptTag({ content: AXE })
  return page.evaluate(async () => {
    const axe = (window as unknown as { axe: { run: (c: Document, o: object) => Promise<{ violations: { id: string; nodes: { target: string[] }[] }[] }> } }).axe
    const result = await axe.run(document, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] })
    return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
  })
}

async function audit(page: Page, path: string, found: Record<string, string[]>, prefix = '') {
  await page.goto(path)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  const list = await violations(page)
  if (list.length > 0) {
    found[prefix + path] = list
  }
}

test('J14: no WCAG 2.1 AA violation on the main screens, in French and in Arabic', async ({ page }) => {
  const found: Record<string, string[]> = {}
  await page.goto('/login')
  const login = await violations(page)
  if (login.length > 0) {
    found['/login'] = login
  }
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page).not.toHaveURL(/\/login$/)
  for (const path of SCREENS) {
    await audit(page, path, found)
  }

  await page.goto('/')
  await page.getByLabel('Langue').selectOption('ar')
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  for (const path of RTL_SCREENS) {
    await audit(page, path, found, 'ar ')
  }
  await page.goto('/')
  await page.getByLabel('اللغة').selectOption('fr')
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')

  expect(found).toEqual({})
})
