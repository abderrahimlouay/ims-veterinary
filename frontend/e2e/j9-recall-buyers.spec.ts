import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct, payByCheque } from './sales'
import { logIn } from './session'

/**
 * J9 (testing.md §8): real documents, then the recall of their lot. A delivery note for a customer, converted to an
 * invoice with its lines frozen; an anonymous receipt; a receipt cancelled with a reason. The buyers list has the note
 * once and the anonymous sale, never the cancelled receipt (W6, W11, BR-LOT-008, ADR-119, ADR-125). Fictional data only.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J9: documents, conversion, cancellation, then the recall and its buyers', async ({ page }) => {
  const customer = 'E2E Ferme Rappel'
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await nav.getByRole('link', { name: 'Clients', exact: true }).click()
  await page.getByRole('link', { name: 'Nouveau client' }).click()
  await page.getByLabel('Nom', { exact: true }).fill(customer)
  await page.getByLabel('Téléphone').fill('0100 00 00 19')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: customer })).toBeVisible()
  await aProduct(page, 'E2E-J9-RCL', 'E2E Rappelval', '10.0000')
  await aLot(page, 'E2E Rappelval', 'J9-LOT', '12/2029', '20')

  await page.goto('/pos')
  await page.getByLabel('Document').selectOption('delivery_note')
  await page.getByLabel('Client').fill('Ferme Rappel')
  await page.getByRole('button', { name: `Choisir ${customer}` }).click()
  await addToCart(page, 'E2E Rappelval', '2')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByText(/Document DN-/)).toBeVisible()
  const note = ((await page.getByText(/Document DN-/).textContent()) ?? '').replace('Document ', '')
  await page.getByRole('button', { name: 'Nouvelle vente' }).click()

  await addToCart(page, 'E2E Rappelval', '1')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  const anonymous = await payByCheque(page)
  await page.getByRole('button', { name: 'Nouvelle vente' }).click()

  await addToCart(page, 'E2E Rappelval', '3')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  const cancelled = await payByCheque(page)

  await page.goto('/sales')
  await page.getByLabel('Numéro').fill(note)
  await page.getByRole('link', { name: note }).click()
  await page.getByRole('button', { name: 'Convertir en : Facture' }).click()
  await expect(page.getByTestId('pos-customer')).toHaveText(customer)
  await expect(page.getByTestId('pos-total')).toContainText('21,80')
  const invoice = await payByCheque(page)
  expect(invoice).toMatch(/^INV-/)

  await page.goto('/sales')
  await page.getByLabel('Numéro').fill(cancelled)
  await page.getByRole('link', { name: cancelled }).click()
  await page.getByRole('button', { name: 'Annuler le document' }).click()
  await page.getByLabel('Motif').fill('Erreur de caisse')
  await page.getByRole('button', { name: 'Confirmer' }).click()
  await expect(page.getByTestId('sale-status')).toHaveText('Annulé')
  await expect(page.getByText('Erreur de caisse')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: cancelled })).toBeVisible()

  await page.goto('/lots')
  await page.getByRole('link', { name: 'J9-LOT' }).click()
  await page.getByRole('button', { name: 'Rappeler' }).click()
  await page.getByLabel('Motif').fill('Avis du fabricant')
  await page.getByRole('button', { name: 'Confirmer' }).click()
  const rows = page.getByRole('row')
  await expect(rows.filter({ hasText: note })).toContainText(customer)
  await expect(rows.filter({ hasText: note })).toContainText('0100 00 00 19')
  await expect(rows.filter({ hasText: anonymous })).toContainText('Vente au comptoir')
  await expect(rows.filter({ hasText: cancelled })).toHaveCount(0)
  await expect(rows.filter({ hasText: invoice })).toHaveCount(0)
})
