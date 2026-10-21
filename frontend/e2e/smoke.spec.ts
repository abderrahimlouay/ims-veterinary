import { expect, test } from '@playwright/test'

test('the login page loads in French and switches to right-to-left Arabic', async ({ page }) => {
  await page.goto('/login')

  await expect(page).toHaveTitle('IMS Vétérinaire')
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr')
  await expect(page.getByRole('heading', { level: 1, name: 'Connexion' })).toBeVisible()

  await page.getByLabel('Langue').selectOption('ar')

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  await expect(page.getByRole('heading', { level: 1, name: 'تسجيل الدخول' })).toBeVisible()

  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
})

test('a page behind the login sends an anonymous visitor to the login page', async ({ page }) => {
  await page.goto('/does-not-exist')

  await expect(page).toHaveURL(/\/login\?redirect=%2Fdoes-not-exist$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Connexion' })).toBeVisible()
})
