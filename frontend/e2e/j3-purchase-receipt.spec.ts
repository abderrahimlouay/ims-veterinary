import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J3 (testing.md §8): a supplier, a product, a purchase order validated and sent, its receipt into a new lot, the
 * lot visible with its stock and its receipt movement, the order partially received, then the receipt cancelled
 * and the stock back to zero (W1a, W1b, W1d, ADR-093…106). Runs with J8, after the journeys that expect no lot yet.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J3: purchase order, goods receipt, lot in stock, receipt cancelled', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })

  await nav.getByRole('link', { name: 'Fournisseurs', exact: true }).click()
  await page.getByRole('link', { name: 'Nouveau fournisseur' }).click()
  await page.getByLabel('Nom', { exact: true }).fill('E2E Biolab')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'E2E Biolab' })).toBeVisible()

  await nav.getByRole('link', { name: 'Produits' }).click()
  await page.getByRole('link', { name: 'Nouveau produit' }).click()
  await page.getByLabel('SKU', { exact: true }).fill('E2E-J3-PUR')
  await page.getByLabel('Nom commercial').fill('E2E Purchaseval')
  await page.getByLabel('Unité de base', { exact: true }).fill('tablet')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'E2E Purchaseval' })).toBeVisible()

  await nav.getByRole('link', { name: 'Bons de commande' }).click()
  await page.getByRole('link', { name: 'Nouveau bon de commande' }).click()
  await page.getByLabel('Fournisseur').selectOption({ label: 'E2E Biolab' })
  await page.getByLabel('Ajouter un produit').fill('E2E Purchaseval')
  await page.getByRole('button', { name: 'Choisir E2E Purchaseval' }).click()
  // The price is prefilled from the product (purchase price 0): wait for it before typing ours.
  await expect(page.getByLabel('Prix unitaire')).toHaveValue('0.0000')
  await page.getByLabel('Quantité').fill('30')
  await page.getByLabel('Prix unitaire').fill('0.5')
  await page.getByRole('button', { name: 'Enregistrer le brouillon' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /^PO-\d{4}-\d{6}$/ })).toBeVisible()
  await expect(page.getByText('TTC 16,35 DA')).toBeVisible()

  await page.getByRole('button', { name: 'Valider' }).click()
  await expect(page.getByText('Validé', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Marquer envoyé' }).click()
  await expect(page.getByText('Envoyé', { exact: true }).first()).toBeVisible()

  await page.getByRole('link', { name: 'Réceptionner' }).click()
  await expect(page.getByLabel('Quantité')).toHaveValue('30.000')
  await page.getByLabel('Quantité').fill('20')
  await page.getByLabel('N° de lot').fill('J3-L1')
  await page.getByLabel('Date de péremption').fill('12/2027')
  await page.getByLabel('Coût par unité de base').fill('0.5')
  await page.getByRole('button', { name: 'Confirmer la réception' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /^GR-\d{4}-\d{6}$/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /^PO-/ })).toBeVisible()
  const receiptUrl = page.url()

  await page.getByRole('link', { name: 'J3-L1' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Lot J3-L1' })).toBeVisible()
  await expect(page.getByText('20,000 tablet').first()).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Réception', exact: true })).toBeVisible()

  await page.goto(receiptUrl)
  await page.getByRole('link', { name: /^PO-/ }).click()
  await expect(page.getByText('Reçu en partie', { exact: true })).toBeVisible()

  await page.goto(receiptUrl)
  await page.getByRole('button', { name: 'Annuler la réception' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Motif').fill('Wrong delivery')
  await dialog.getByRole('button', { name: 'Confirmer' }).click()
  await expect(page.getByText('Annulée', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'J3-L1' }).click()
  await expect(page.getByText('0,000 tablet').first()).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Annulation de réception' })).toBeVisible()
})
