import { expect, test } from '@playwright/test'
import { ADMIN } from './fixtures'
import { changePassword, logIn, logOut } from './session'

/**
 * J1 (testing.md §8): first login of the bootstrapped administrator with the forced password change
 * (ADR-039/040), logout, login again. Stateful, so serial and never retried.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J1: first login, forced password change, logout and login again', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  await logIn(page, ADMIN.username, 'Not-the-password-1')
  await expect(page.getByRole('alert')).toHaveText("Nom d'utilisateur ou mot de passe incorrect.")

  await logIn(page, ADMIN.username.toUpperCase(), ADMIN.bootstrapPassword)
  await expect(page).toHaveURL(/\/account$/)
  await expect(page.getByText('Votre mot de passe est temporaire')).toBeVisible()

  await page.goto('/')
  await expect(page).toHaveURL(/\/account$/)

  await changePassword(page, ADMIN.bootstrapPassword, ADMIN.newPassword)

  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await expect(page.getByRole('banner')).toContainText('E2E Administrator')

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()

  await page.goto('/nowhere')
  await expect(page.getByRole('heading', { level: 1, name: 'Page introuvable' })).toBeVisible()

  await logOut(page)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  await logIn(page, ADMIN.username, ADMIN.bootstrapPassword)
  await expect(page.getByRole('alert')).toBeVisible()
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
})
