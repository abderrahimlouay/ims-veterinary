import { expect, type Page } from '@playwright/test'

/** A sellable product through the forms, with its first opening-stock lot. */
export async function aProduct(page: Page, sku: string, name: string, price: string) {
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await nav.getByRole('link', { name: 'Produits' }).click()
  await page.getByRole('link', { name: 'Nouveau produit' }).click()
  await page.getByLabel('SKU', { exact: true }).fill(sku)
  await page.getByLabel('Nom commercial').fill(name)
  await page.getByLabel('Unité de base', { exact: true }).fill('tablet')
  await page.getByLabel('Prix de vente (unité de base)').fill(price)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
}

/** One lot of the product through the opening-stock page. */
export async function aLot(page: Page, name: string, lot: string, expiry: string, quantity: string) {
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await nav.getByRole('link', { name: 'Stock', exact: true }).click()
  await page.getByRole('link', { name: "Stock d'ouverture" }).click()
  await expect(page.getByRole('heading', { level: 1, name: "Stock d'ouverture" })).toBeVisible()
  await page.getByLabel('Rechercher un produit').fill(name)
  await page.getByRole('button', { name: `Choisir ${name}` }).click()
  await page.getByLabel('N° de lot').fill(lot)
  await page.getByLabel('Date de péremption').fill(expiry)
  await page.getByLabel('Quantité', { exact: true }).fill(quantity)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByText(new RegExp(`Lot ${lot} enregistré`))).toBeVisible()
}

/** Pays what is in the cart by cheque, which needs no cash session, and returns the document number. */
export async function payByCheque(page: Page): Promise<string> {
  await page.getByLabel('Mode').selectOption('cheque')
  await page.getByLabel('Référence').fill('CHQ-E2E')
  await page.getByRole('button', { name: 'Confirmer la vente' }).click()
  const number = await page.getByText(/^Document /).textContent()
  return (number ?? '').replace('Document ', '')
}

export async function addToCart(page: Page, name: string, quantity: string) {
  await page.getByLabel('Quantité', { exact: true }).fill(quantity)
  await page.getByLabel('Scanner ou chercher un produit').fill(name)
  await page.getByRole('button', { name: `Ajouter ${name}` }).click()
}
