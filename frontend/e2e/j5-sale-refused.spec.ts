import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct } from './sales'
import { logIn } from './session'

/**
 * J5 (testing.md §8): sales refused by the server. More than the stock; a product whose only lot has expired; a lot
 * blocked after it went into the cart, the line highlighted at confirmation and nothing sold (BR-LOT-003/004).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J5: insufficient stock, an expired lot and a lot blocked meanwhile', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J5-OLD', 'E2E Perimoval', '10.0000')
  await aLot(page, 'E2E Perimoval', 'J5-OLD', '01/2020', '5')
  await aProduct(page, 'E2E-J5-BLK', 'E2E Blockval', '10.0000')
  await aLot(page, 'E2E Blockval', 'J5-BLK', '12/2029', '5')

  await page.goto('/pos')
  await addToCart(page, 'E2E Blockval', '1000')
  await expect(page.getByRole('alert')).toContainText(/stock/i)
  await expect(page.getByTestId('pos-line')).toHaveCount(0)

  await addToCart(page, 'E2E Perimoval', '1')
  await expect(page.getByRole('alert')).toContainText(/stock/i)
  await expect(page.getByTestId('pos-line')).toHaveCount(0)

  await addToCart(page, 'E2E Blockval', '2')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  await expect(page.getByRole('alert')).toHaveCount(0)

  const pharmacy = await page.context().newPage()
  await pharmacy.goto('/lots')
  await pharmacy.getByRole('link', { name: 'J5-BLK' }).click()
  await pharmacy.getByRole('button', { name: 'Bloquer' }).click()
  await pharmacy.getByLabel('Motif').fill('Suspicion')
  await pharmacy.getByRole('button', { name: 'Confirmer' }).click()
  await expect(pharmacy.getByRole('button', { name: 'Débloquer' })).toBeVisible()
  await pharmacy.close()

  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByRole('alert')).toContainText('Ce lot est bloqué')
  await expect(page.getByTestId('pos-line')).toHaveAttribute('data-invalid', 'true')
  await expect(page.getByRole('heading', { level: 1, name: 'Caisse' })).toBeVisible()
})
