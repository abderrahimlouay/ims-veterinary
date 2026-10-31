import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct } from './sales'
import { logIn } from './session'

/**
 * J4 (testing.md §8): a counter sale spanning two lots by FEFO, paid in cash with change. The cash session is opened
 * here, from the refusal of a sale without one; the expected cash grows by the total, never by what was tendered
 * (ADR-118).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J4: a cash sale across two lots by FEFO, with change', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J4-FEFO', 'E2E Fefoval', '10.0000')
  await aLot(page, 'E2E Fefoval', 'J4-LATE', '12/2029', '10')
  await aLot(page, 'E2E Fefoval', 'J4-SOON', '12/2028', '3')

  await page.getByRole('navigation', { name: 'Navigation principale' }).getByRole('link', { name: 'Caisse' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Caisse' })).toBeVisible()
  await addToCart(page, 'E2E Fefoval', '5')

  const lines = page.getByTestId('pos-line')
  await expect(lines).toHaveCount(2)
  await expect(lines.nth(0)).toContainText('J4-SOON')
  await expect(lines.nth(0)).toContainText('3,000')
  await expect(lines.nth(1)).toContainText('J4-LATE')
  await expect(lines.nth(1)).toContainText('2,000')
  await page.getByLabel('Espèces reçues').fill('100')
  await expect(page.getByTestId('pos-change')).toBeVisible()

  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByRole('dialog', { name: 'Ouvrir une session de caisse' })).toBeVisible()
  await page.getByLabel('Fonds de caisse').fill('50')
  await page.getByRole('dialog').getByRole('button', { name: 'Ouvrir la session' }).click()
  await expect(page.getByTestId('cash-session')).toContainText('Session de caisse ouverte')

  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Vente confirmée' })).toBeVisible()
  await expect(page.getByText(/Document RCT-\d{4}-\d{6}/)).toBeVisible()
  await expect(page.getByText(/Monnaie à rendre/)).toBeVisible()

  await page.getByRole('button', { name: 'Nouvelle vente' }).click()
  await expect(page.getByText('Le panier est vide.')).toBeVisible()
  await expect(page.getByTestId('cash-session')).toContainText(/104,50/)
})
