import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct } from './sales'
import { logIn } from './session'

/**
 * J6 (testing.md §8): a cash sale of two products, then a customer return of one sealed unit back into stock and one
 * opened unit discarded with its reason (W3, ADR-127…131). The credit note is issued with its own number and refunded
 * in cash, which lowers the session's expected cash; the lot ledger shows the return and the discard; a second return
 * beyond what is left is refused; the returned sale can no longer be cancelled. Runs after the sales journeys, in its
 * own project, because it uses the administrator's cash session. Fictional data only.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

async function expectedCash(page: Page): Promise<number> {
  const response = await page.request.get('/api/cash-sessions/current')
  expect(response.status()).toBe(200)
  return Number(((await response.json()) as { expectedAmount: string }).expectedAmount)
}

test('J6: a customer return with a credit note, a restock, a discard and a cash refund', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J6-SEALED', 'E2E Retourval', '10.0000')
  await aLot(page, 'E2E Retourval', 'J6-SEALED', '12/2029', '10')
  await aProduct(page, 'E2E-J6-OPENED', 'E2E Ouvertval', '10.0000')
  await aLot(page, 'E2E Ouvertval', 'J6-OPENED', '12/2029', '10')

  await page.goto('/pos')
  await expect(page.getByRole('heading', { level: 1, name: 'Caisse' })).toBeVisible()
  // Ask the server, not the bar: while the session loads, the bar shows "none" for a moment.
  const current = await page.request.get('/api/cash-sessions/current')
  if (current.status() === 204) {
    await page.getByTestId('cash-session').getByRole('button', { name: 'Ouvrir la session' }).click()
    await page.getByLabel('Fonds de caisse').fill('100')
    await page.getByRole('dialog').getByRole('button', { name: 'Ouvrir la session' }).click()
  }
  await expect(page.getByTestId('cash-session')).toContainText('Session de caisse ouverte')
  await addToCart(page, 'E2E Retourval', '2')
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  await addToCart(page, 'E2E Ouvertval', '2')
  await expect(page.getByTestId('pos-line')).toHaveCount(2)
  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByText(/Document RCT-/)).toBeVisible()
  const receipt = ((await page.getByText(/Document RCT-/).textContent()) ?? '').replace('Document ', '')
  const beforeRefund = await expectedCash(page)

  await page.goto('/sales')
  await page.getByLabel('Numéro').fill(receipt)
  await page.getByRole('link', { name: receipt }).click()
  await page.getByRole('link', { name: 'Retour de marchandise' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Nouveau retour client' })).toBeVisible()
  await page.getByLabel('Quantité retournée de E2E Retourval (unité de base)').fill('1')
  await page.getByLabel('Quantité retournée de E2E Ouvertval (unité de base)').fill('1')
  await page.getByLabel('E2E Ouvertval est scellé').uncheck()
  await page.getByLabel('Motif de destruction de E2E Ouvertval').selectOption('opened')
  await page.getByLabel('Motif du retour').fill('Erreur de produit')
  await page.getByRole('button', { name: 'Vérifier' }).click()
  await expect(page.getByTestId('credit-total')).toContainText('21,80')
  await page.getByRole('button', { name: 'Confirmer le retour' }).click()

  await expect(page.getByRole('heading', { level: 1, name: /^CR-\d{4}-\d{6}$/ })).toBeVisible()
  await expect(page.getByTestId('credit-note')).toContainText(/CN-\d{4}-\d{6}/)
  await expect(page.getByText(/Destruction · Ouvert/)).toBeVisible()
  expect(await expectedCash(page)).toBeCloseTo(beforeRefund - 21.8, 2)

  await page.getByRole('link', { name: 'J6-OPENED' }).click()
  await expect(page.getByRole('cell', { name: 'Retour client' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Destruction au retour' })).toBeVisible()

  await page.goto('/sales')
  await page.getByLabel('Numéro').fill(receipt)
  await page.getByRole('link', { name: receipt }).click()
  await expect(page.getByRole('columnheader', { name: 'Retourné' })).toBeVisible()
  await expect(page.getByRole('link', { name: /^CR-/ })).toBeVisible()
  await page.getByRole('link', { name: 'Retour de marchandise' }).click()
  await page.getByLabel('Quantité retournée de E2E Retourval (unité de base)').fill('2')
  await page.getByLabel('Motif du retour').fill('Encore')
  await page.getByRole('button', { name: 'Vérifier' }).click()
  await expect(page.getByText('Plus que ce qui reste à retourner sur cette ligne.')).toBeVisible()

  await page.goto('/sales')
  await page.getByLabel('Numéro').fill(receipt)
  await page.getByRole('link', { name: receipt }).click()
  await page.getByRole('button', { name: 'Annuler le document' }).click()
  await page.getByLabel('Motif').fill('Erreur de caisse')
  await page.getByRole('button', { name: 'Confirmer' }).click()
  await expect(page.getByText('Ce document ne peut pas être annulé : utilisez un avoir.')).toBeVisible()
})
