import { expect, type Page } from '@playwright/test'
import { ADMIN, OWN_USER_PASSWORDS } from './fixtures'
import { changePassword, logIn, logOut } from './session'

export interface OwnUser {
  id: number
  username: string
  fullName: string
}

type Role = 'Administrator' | 'Pharmacist' | 'Seller' | 'Accountant'

/**
 * A user of the journey's own, created through the users page by whoever is logged in (with `user.manage`). Each
 * journey that sells or uses a cash session has its own user, so the journeys share no session and run in any order.
 */
export async function aUser(page: Page, key: string, role: Role): Promise<OwnUser> {
  const username = `e2e.${key}`
  const fullName = `E2E ${key}`
  await page.goto('/admin/users/new')
  await page.getByLabel("Nom d'utilisateur").fill(username)
  await page.getByLabel('Nom complet').fill(fullName)
  await page.getByLabel('Rôle').selectOption({ label: role })
  await page.getByLabel('Mot de passe initial').fill(OWN_USER_PASSWORDS.initial)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: username })).toBeVisible()
  const id = Number(/\/admin\/users\/(\d+)/.exec(page.url())?.[1])
  expect(id).toBeGreaterThan(0)
  return { id, username, fullName }
}

/** Logs out, then in as `user` for the first time, choosing its password; ends on the dashboard. */
export async function logInFirstTime(page: Page, user: OwnUser) {
  await logOut(page)
  await logIn(page, user.username, OWN_USER_PASSWORDS.initial)
  await expect(page).toHaveURL(/\/account$/)
  await changePassword(page, OWN_USER_PASSWORDS.initial, OWN_USER_PASSWORDS.chosen)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
}

/** The bootstrapped administrator creates the journey's own user with `role`, who is then logged in. */
export async function asOwnUser(page: Page, key: string, role: Role = 'Administrator'): Promise<OwnUser> {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  const user = await aUser(page, key, role)
  await logInFirstTime(page, user)
  return user
}

/** Opens the logged-in user's cash session from the counter's bar; the user has none open yet. */
export async function openCashSession(page: Page, fund: string) {
  await page.goto('/pos')
  const bar = page.getByTestId('cash-session')
  await bar.getByRole('button', { name: 'Ouvrir la session' }).click()
  await page.getByRole('dialog').getByLabel('Fonds de caisse').fill(fund)
  await page.getByRole('dialog').getByRole('button', { name: 'Ouvrir la session' }).click()
  await expect(bar).toContainText('Session de caisse ouverte')
}
