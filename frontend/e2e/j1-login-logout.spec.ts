import { expect, test, type Page } from '@playwright/test'
import { ADMIN } from './fixtures'

/**
 * J1 (testing.md §8): first login of the bootstrapped administrator with the forced password change
 * (ADR-039/040), logout, login again. Stateful, so serial and never retried.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

async function logIn(page: Page, username: string, password: string) {
  await page.getByLabel("Nom d'utilisateur").fill(username)
  await page.getByLabel('Mot de passe', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Se connecter' }).click()
}

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

  await page.getByLabel('Mot de passe actuel', { exact: true }).fill(ADMIN.bootstrapPassword)
  await page.getByLabel('Nouveau mot de passe', { exact: true }).fill(ADMIN.newPassword)
  await page.getByLabel('Confirmer le nouveau mot de passe').fill(ADMIN.newPassword)
  await page.getByRole('button', { name: 'Changer le mot de passe' }).click()

  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await expect(page.getByRole('banner')).toContainText('E2E Administrator')

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()

  await page.goto('/nowhere')
  await expect(page.getByRole('heading', { level: 1, name: 'Page introuvable' })).toBeVisible()

  await page.getByRole('button', { name: 'Se déconnecter' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  await logIn(page, ADMIN.username, ADMIN.bootstrapPassword)
  await expect(page.getByRole('alert')).toBeVisible()
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
})
