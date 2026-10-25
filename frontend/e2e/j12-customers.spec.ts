import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J12 (testing.md §8, Phase 6): a customer category, a product, then a customer created through the form; the
 * customer found again by searching the phone in another format (ADR-110); an own price set on the Prices tab
 * (ADR-111); the credit limit moved through its three states — cash only for a new customer, no limit, a limit
 * (ADR-108). Fictional data only, phone of the reserved fake range (ADR-114). Runs last (`customers` project).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

const CUSTOMER = 'E2E Ferme Démo'
const PHONE = '0100 00 00 12'

test('J12: create a customer, find them by phone, set a price and a credit limit', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })

  await nav.getByRole('link', { name: 'Catégories de clients' }).click()
  await page.getByLabel('Nom', { exact: true }).fill('E2E Éleveurs')
  await page.getByLabel('Remise (%)').fill('5')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByText('E2E Éleveurs')).toBeVisible()

  await nav.getByRole('link', { name: 'Produits' }).click()
  await page.getByRole('link', { name: 'Nouveau produit' }).click()
  await page.getByLabel('SKU', { exact: true }).fill('E2E-J12-CUS')
  await page.getByLabel('Nom commercial').fill('E2E Customerval')
  await page.getByLabel('Unité de base', { exact: true }).fill('tablet')
  await page.getByLabel('Prix de vente (unité de base)').fill('0.5000')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'E2E Customerval' })).toBeVisible()

  await nav.getByRole('link', { name: 'Clients', exact: true }).click()
  await page.getByRole('link', { name: 'Nouveau client' }).click()
  await page.getByLabel('Nom', { exact: true }).fill(CUSTOMER)
  await page.getByLabel('Catégorie').selectOption({ label: 'E2E Éleveurs (5.00 %)' })
  await page.getByLabel('Téléphone').fill(PHONE)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: CUSTOMER })).toBeVisible()
  await expect(page.getByTestId('credit-limit')).toHaveText('Comptant uniquement')

  await nav.getByRole('link', { name: 'Clients', exact: true }).click()
  await page.getByLabel('Nom, téléphone ou NIF').fill('+213 100-000-012')
  await page.getByRole('link', { name: CUSTOMER }).click()

  await page.getByRole('tab', { name: 'Prix' }).click()
  await page.getByLabel('Chercher un produit').fill('E2E Customerval')
  await page.getByRole('button', { name: /E2E Customerval/ }).click()
  await page.getByLabel('Prix par unité de base').fill('0.4200')
  await page.getByRole('button', { name: 'Enregistrer le prix' }).click()
  await expect(page.getByRole('row', { name: /E2E Customerval/ }).getByText(/0,42/)).toBeVisible()

  await page.getByRole('tab', { name: 'Aperçu' }).click()
  const changeLimit = page.getByRole('button', { name: 'Modifier le plafond' })
  const dialog = page.getByRole('dialog')
  await changeLimit.click()
  await dialog.getByRole('radio', { name: /^Aucun plafond/ }).check()
  await dialog.getByRole('button', { name: 'Enregistrer le plafond' }).click()
  await expect(page.getByTestId('credit-limit')).toHaveText('Aucun plafond')

  await changeLimit.click()
  await dialog.getByRole('radio', { name: /^Plafond/ }).check()
  await dialog.getByLabel('Montant du plafond').fill('50000')
  await dialog.getByRole('button', { name: 'Enregistrer le plafond' }).click()
  await expect(page.getByTestId('credit-limit')).toHaveText(/^Plafond : 50\s000,00/)
})
