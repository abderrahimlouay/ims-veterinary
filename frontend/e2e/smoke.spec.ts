import { expect, test } from '@playwright/test'

test('the shell loads in French and switches to right-to-left Arabic', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveTitle('IMS Vétérinaire')
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr')
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()

  await page.getByLabel('Langue').selectOption('ar')

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  await expect(page.getByRole('heading', { level: 1, name: 'لوحة القيادة' })).toBeVisible()

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
})

test('an unknown route shows the not-found page', async ({ page }) => {
  await page.goto('/does-not-exist')

  await expect(page.getByRole('heading', { level: 1, name: 'Page introuvable' })).toBeVisible()
})
