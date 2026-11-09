import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct, payByCheque } from './sales'
import { logIn } from './session'
import { aUser, logInFirstTime } from './users'
import { sidebar } from './navigation'

/**
 * The sales part of J11 (testing.md §8): a Seller sells at the counter and reads the document, but has no way to cancel
 * it (`sale.cancel`, ADR-119), to return it (`return.customer.create`, ADR-131) or to see the settings of credit. The server refuses anyway (`EndpointPermissionMatrixTest`).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J11 sales: a Seller sells but cannot cancel nor return', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J11-SAL', 'E2E Sellerval', '10.0000')
  await aLot(page, 'E2E Sellerval', 'J11-LOT', '12/2029', '5')
  const seller = await aUser(page, 'j11.seller', 'Seller')
  await logInFirstTime(page, seller)
  await (await sidebar(page)).getByRole('link', { name: 'Caisse', exact: true }).click()
  await addToCart(page, 'E2E Sellerval', '1')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  const number = await payByCheque(page)

  await page.goto('/sales')
  await page.getByRole('link', { name: number }).click()
  await expect(page.getByTestId('sale-status')).toHaveText('Confirmé')
  await expect(page.getByRole('button', { name: 'Annuler le document' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Retour de marchandise' })).toHaveCount(0)
  await page.goto('/returns/customer/new')
  await expect(page.getByRole('heading', { level: 1, name: 'Accès refusé' })).toBeVisible()
  await page.goto('/admin/settings')
  await expect(page.getByRole('heading', { level: 1, name: 'Accès refusé' })).toBeVisible()
})
