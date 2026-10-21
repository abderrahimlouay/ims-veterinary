import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'
import { logIn } from './session'

/**
 * J2 (testing.md §8), Phase 2 part: reference data, product creation, ingredient and target species, search
 * by name, ingredient and species, archive. Units and barcodes complete J2 in Phase 3. Runs after J1.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

async function search(page: Page, text: string) {
  await page.getByLabel('Rechercher').fill(text)
}

test('J2 (Phase 2): create, describe, find and archive a product', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })

  await nav.getByRole('link', { name: 'Données de référence' }).click()
  await page.getByRole('tab', { name: 'Principes actifs (DCI)' }).click()
  await page.getByLabel('Nouvelle entrée').fill('E2E Amoxicilline')
  await page.getByRole('button', { name: 'Ajouter' }).click()
  await expect(page.getByText('E2E Amoxicilline')).toBeVisible()

  await nav.getByRole('link', { name: 'Produits' }).click()
  await page.getByRole('link', { name: 'Nouveau produit' }).click()
  await page.getByLabel('SKU', { exact: true }).fill('E2E-AMX-500')
  await page.getByLabel('Nom commercial').fill('E2E Amoxival 500')
  await page.getByLabel('Catégorie thérapeutique').selectOption({ label: 'Antibiotique' })
  await page.getByLabel('Unité de base', { exact: true }).fill('tablet')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'E2E Amoxival 500' })).toBeVisible()

  await page.getByRole('tab', { name: 'Principes actifs' }).click()
  await page.getByRole('button', { name: 'Ajouter un principe actif' }).click()
  await page.getByLabel('Principe actif 1').selectOption({ label: 'E2E Amoxicilline' })
  await page.getByLabel('Dosage 1').fill('500 mg')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByText('Modifications enregistrées.')).toBeVisible()

  await page.getByRole('tab', { name: "Espèces et délais d'attente" }).click()
  await page.getByLabel('Bovin').check()
  await page.getByRole('button', { name: 'Enregistrer les espèces cibles' }).click()
  await expect(page.getByText('Modifications enregistrées.')).toBeVisible()

  await nav.getByRole('link', { name: 'Produits' }).click()
  const product = page.getByRole('link', { name: 'E2E Amoxival 500' })
  await search(page, 'amoxival 5')
  await expect(product).toBeVisible()
  await search(page, 'e2e amoxicilline')
  await expect(product).toBeVisible()
  await search(page, 'E2E')
  await page.getByLabel('Espèce').selectOption({ label: 'Bovin' })
  await expect(product).toBeVisible()

  await product.click()
  await page.getByRole('button', { name: 'Archiver' }).click()
  await expect(page.getByRole('button', { name: 'Restaurer' })).toBeVisible()
  await nav.getByRole('link', { name: 'Produits' }).click()
  await search(page, 'E2E Amoxival')
  await expect(page.getByText('Aucun produit ne correspond à la recherche.')).toBeVisible()
  await page.getByLabel('Afficher les produits archivés').check()
  await expect(page.getByRole('link', { name: 'E2E Amoxival 500' })).toBeVisible()
})
