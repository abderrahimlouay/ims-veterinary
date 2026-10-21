import { expect, type Page } from '@playwright/test'

export async function logIn(page: Page, username: string, password: string) {
  await page.getByLabel("Nom d'utilisateur").fill(username)
  await page.getByLabel('Mot de passe', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Se connecter' }).click()
}

export async function changePassword(page: Page, current: string, next: string) {
  await page.getByLabel('Mot de passe actuel', { exact: true }).fill(current)
  await page.getByLabel('Nouveau mot de passe', { exact: true }).fill(next)
  await page.getByLabel('Confirmer le nouveau mot de passe').fill(next)
  await page.getByRole('button', { name: 'Changer le mot de passe' }).click()
}

export async function logOut(page: Page) {
  await page.getByRole('button', { name: 'Se déconnecter' }).click()
  await expect(page).toHaveURL(/\/login$/)
}
