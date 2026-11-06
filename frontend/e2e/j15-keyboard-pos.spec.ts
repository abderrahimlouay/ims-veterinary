import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, aProduct } from './sales'
import { logIn } from './session'

/**
 * J15 (frontend.md §12, testing.md §8): a whole counter sale with the keyboard only — the search box has the focus,
 * the product is found and added, the cash received typed, the sale confirmed, and a cash session opened from the
 * dialog when none is open — Tab, typing and Enter, no click. The product and its lot are prepared with the usual
 * forms. Runs last, on the administrator's cash session, open or not.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

/** The accessible name of the focused element: its label, aria-label or text. */
async function focusedName(page: Page): Promise<string> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null
    if (!el) {
      return ''
    }
    const labelled = el.getAttribute('aria-labelledby')
    const fromLabel = (el as HTMLInputElement).labels?.[0]?.textContent ?? ''
    return (el.getAttribute('aria-label') ?? (labelled ? document.getElementById(labelled)?.textContent : '') ?? '')
      || fromLabel || el.textContent || ''
  }).then((name) => name.trim())
}

/** Presses Tab until the element named `name` has the focus; fails if it is not reachable. */
async function tabTo(page: Page, name: string | RegExp) {
  for (let i = 0; i < 80; i += 1) {
    const current = await focusedName(page)
    if (typeof name === 'string' ? current === name : name.test(current)) {
      return
    }
    await page.keyboard.press('Tab')
  }
  throw new Error(`${String(name)} cannot be reached with the Tab key`)
}

test('J15: a counter sale with the keyboard only', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J15-KEY', 'E2E Clavierine', '20.0000')
  await aLot(page, 'E2E Clavierine', 'J15-LOT', '12/2029', '10')

  await page.goto('/pos')
  await expect(page.getByLabel('Scanner ou chercher un produit')).toBeFocused()
  await page.keyboard.type('E2E Clavierine')
  await tabTo(page, 'Ajouter E2E Clavierine')
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)

  await tabTo(page, 'Espèces reçues')
  await page.keyboard.type('100')
  await tabTo(page, 'Confirmer la vente')
  await page.keyboard.press('Enter')

  const opening = page.getByRole('dialog', { name: 'Ouvrir une session de caisse' })
  const confirmed = page.getByRole('heading', { level: 1, name: 'Vente confirmée' })
  await expect(opening.or(confirmed)).toBeVisible()
  if (await opening.isVisible()) {
    await tabTo(page, 'Fonds de caisse')
    await page.keyboard.type('50')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('cash-session')).toContainText('Session de caisse ouverte')
    await tabTo(page, 'Confirmer la vente')
    await page.keyboard.press('Enter')
  }
  await expect(confirmed).toBeVisible()
  await expect(page.getByText(/Monnaie à rendre/)).toBeVisible()
})
