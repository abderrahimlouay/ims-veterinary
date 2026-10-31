import { expect, test } from '@playwright/test'
import { ADMIN, SELLER } from './fixtures'
import { aLot, addToCart, aProduct, payByCheque } from './sales'
import { logIn, logOut } from './session'

/**
 * The sales part of J11 (testing.md §8): a Seller sells at the counter and reads the document, but has no way to cancel
 * it (`sale.cancel`, ADR-119) or to see the settings of credit. The server refuses anyway (`EndpointPermissionMatrixTest`).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J11 sales: a Seller sells but cannot cancel', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J11-SAL', 'E2E Sellerval', '10.0000')
  await aLot(page, 'E2E Sellerval', 'J11-LOT', '12/2029', '5')
  await logOut(page)

  await logIn(page, SELLER.username, SELLER.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await page.getByRole('navigation', { name: 'Navigation principale' }).getByRole('link', { name: 'Caisse' }).click()
  await addToCart(page, 'E2E Sellerval', '1')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  const number = await payByCheque(page)

  await page.goto('/sales')
  await page.getByRole('link', { name: number }).click()
  await expect(page.getByTestId('sale-status')).toHaveText('Confirmé')
  await expect(page.getByRole('button', { name: 'Annuler le document' })).toHaveCount(0)
  await page.goto('/admin/settings')
  await expect(page.getByRole('heading', { level: 1, name: 'Accès refusé' })).toBeVisible()
})
