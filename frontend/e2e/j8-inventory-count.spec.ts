import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J8 (testing.md §8): opening stock for a new product, a cycle count on it, a counted quantity below the balance,
 * regularisation, and the ledger showing both movements (W10a, W5, ADR-072…075). Runs after the journeys that
 * expect no lot yet.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J8: opening stock, inventory count and regularisation', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })

  await nav.getByRole('link', { name: 'Produits' }).click()
  await page.getByRole('link', { name: 'Nouveau produit' }).click()
  await page.getByLabel('SKU', { exact: true }).fill('E2E-J8-CNT')
  await page.getByLabel('Nom commercial').fill('E2E Countval')
  await page.getByLabel('Unité de base', { exact: true }).fill('tablet')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'E2E Countval' })).toBeVisible()

  await nav.getByRole('link', { name: 'Stock' }).click()
  await page.getByRole('link', { name: "Stock d'ouverture" }).click()
  await page.getByLabel('Rechercher un produit').fill('E2E Countval')
  await page.getByRole('button', { name: 'Choisir E2E Countval' }).click()
  await page.getByLabel('N° de lot').fill('J8-L1')
  await page.getByLabel('Date de péremption').fill('12/2027')
  await page.getByLabel('Quantité', { exact: true }).fill('10')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByText('Lot J8-L1 enregistré : 10,000 tablet en stock.')).toBeVisible()

  await nav.getByRole('link', { name: 'Inventaires' }).click()
  await page.getByRole('link', { name: 'Nouvel inventaire' }).click()
  await page.getByLabel(/Tournant/).check()
  await page.getByLabel('Une liste de produits', { exact: true }).check()
  await page.getByLabel('Rechercher un produit').fill('E2E Countval')
  await page.getByRole('button', { name: 'Ajouter E2E Countval' }).click()
  await page.getByRole('button', { name: "Ouvrir l'inventaire" }).click()

  const cell = page.getByLabel('Quantité comptée — E2E Countval — J8-L1')
  await cell.fill('8')
  await cell.press('Enter')
  const line = page.getByRole('row', { name: /J8-L1/ })
  await expect(line.getByText('Comptée par E2E Administrator')).toBeVisible()
  await expect(line.getByText('−2,000')).toBeVisible()

  await page.getByRole('button', { name: 'Régulariser' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('−2,000 tablet')).toBeVisible()
  await dialog.getByRole('button', { name: 'Régulariser et clôturer' }).click()
  await expect(page.getByText('Clôturé', { exact: true })).toBeVisible()
  await expect(line.getByText('Régularisée')).toBeVisible()

  await line.getByRole('link', { name: 'J8-L1' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Lot J8-L1' })).toBeVisible()
  await expect(page.getByText('8,000 tablet').first()).toBeVisible()
  await expect(page.getByRole('cell', { name: '+10,000 tablet' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '−2,000 tablet' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Inventaire', exact: true })).toBeVisible()
})
