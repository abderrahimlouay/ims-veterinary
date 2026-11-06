import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'
import { aLot, addToCart, aProduct } from './sales'
import { logIn } from './session'

/**
 * J7 (testing.md §8, Phase 9): two credit invoices for one customer; a cheque on the Account tab settles both, oldest
 * first, and leaves an advance; the statement shows the invoices, the payment and a negative closing balance; the
 * payment reversed with its reason reopens both invoices and the advance is gone (ADR-134, ADR-135). Fictional
 * data only (ADR-114).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

const CUSTOMER = 'E2E Ferme Compte'

async function aCreditInvoice(page: Page, quantity: string): Promise<string> {
  await page.goto('/pos')
  await page.getByLabel('Document').selectOption('invoice')
  await page.getByLabel('Client').fill('Ferme Compte')
  await page.getByRole('button', { name: `Choisir ${CUSTOMER}` }).click()
  await page.getByLabel('Vente à crédit').check()
  await addToCart(page, 'E2E Comptaval', quantity)
  await expect(page.getByTestId('pos-line')).toHaveCount(1)
  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  await expect(page.getByText(/Document INV-/)).toBeVisible()
  return ((await page.getByText(/Document INV-/).textContent()) ?? '').replace('Document ', '')
}

test('J7: a payment on account split across two credit invoices, the statement, then the payment reversed', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await aProduct(page, 'E2E-J7-ACC', 'E2E Comptaval', '10.0000')
  await aLot(page, 'E2E Comptaval', 'J7-LOT', '12/2029', '20')

  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await nav.getByRole('link', { name: 'Clients', exact: true }).click()
  await page.getByRole('link', { name: 'Nouveau client' }).click()
  await page.getByLabel('Nom', { exact: true }).fill(CUSTOMER)
  await page.getByLabel('Téléphone').fill('0100 00 00 07')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: CUSTOMER })).toBeVisible()
  const customerUrl = page.url()
  await page.getByRole('button', { name: 'Modifier le plafond' }).click()
  await page.getByRole('dialog').getByRole('radio', { name: /^Aucun plafond/ }).check()
  await page.getByRole('dialog').getByRole('button', { name: 'Enregistrer le plafond' }).click()
  await expect(page.getByTestId('credit-limit')).toHaveText('Aucun plafond')

  const first = await aCreditInvoice(page, '1')
  const second = await aCreditInvoice(page, '2')

  await page.goto(customerUrl)
  await page.getByRole('tab', { name: 'Compte' }).click()
  await expect(page.getByTestId('open-invoice')).toHaveCount(2)
  await expect(page.getByTestId('open-invoice').first()).toContainText(first)
  await page.getByRole('button', { name: 'Enregistrer un paiement' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Montant').fill('100')
  await dialog.getByLabel('Référence (n° de chèque ou de virement)').fill('CHQ-J7')
  await dialog.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(dialog).toBeHidden()

  await expect(page.getByText('Aucune facture ouverte.')).toBeVisible()
  await expect(page.getByTestId('advance')).toHaveCount(1)
  const lines = page.getByTestId('statement-line')
  await expect(lines).toHaveCount(3)
  await expect(lines.nth(0)).toContainText(first)
  await expect(lines.nth(1)).toContainText(second)
  await expect(lines.nth(2)).toContainText('CHQ-J7')
  await expect(page.getByTestId('closing-balance')).toHaveText(/[-−]/)
  await expect(page.getByTestId('customer-balance')).toHaveText(/[-−]/)

  await lines.nth(2).getByRole('button', { name: 'Annuler le paiement' }).click()
  await dialog.getByLabel('Motif').fill('Chèque rejeté')
  await dialog.getByRole('button', { name: 'Annuler le paiement' }).click()
  await expect(dialog).toBeHidden()

  await expect(page.getByTestId('open-invoice')).toHaveCount(2)
  await expect(page.getByTestId('advance')).toHaveCount(0)
  await expect(lines).toHaveCount(4)
  await expect(lines.nth(3)).toContainText('Annulation de paiement')
  await expect(page.getByTestId('closing-balance')).not.toHaveText(/[-−]/)
})
