import { expect, test, type Page } from '@playwright/test'
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

  await checkPasswordEye(page)

  await logIn(page, ADMIN.username, 'Not-the-password-1')
  await expect(page.getByRole('alert')).toHaveText("Nom d'utilisateur ou mot de passe incorrect.")
  await expect(page.getByLabel('Mot de passe', { exact: true })).toHaveAttribute('type', 'password')

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

/** The eye of the password field (frontend.md §5): hidden by default, toggles, on the end side in both directions. */
async function checkPasswordEye(page: Page) {
  const password = page.getByLabel('Mot de passe', { exact: true })
  await password.fill('Not-the-password-1')
  await expect(password).toHaveAttribute('type', 'password')

  const eye = page.getByRole('button', { name: 'Afficher le mot de passe' })
  await eye.click()
  await expect(password).toHaveAttribute('type', 'text')
  await expect(page.getByRole('button', { name: 'Masquer le mot de passe' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Masquer le mot de passe' }).click()
  await expect(password).toHaveAttribute('type', 'password')
  expect(await isOnRightHalf(page, 'Afficher le mot de passe', 'Mot de passe')).toBe(true)

  await page.getByRole('combobox').selectOption('ar')
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  expect(await isOnRightHalf(page, 'إظهار كلمة المرور', 'كلمة المرور')).toBe(false)
  await page.getByRole('combobox').selectOption('fr')
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr')
  await password.clear()
}

async function isOnRightHalf(page: Page, eyeName: string, fieldLabel: string) {
  const eye = await page.getByRole('button', { name: eyeName }).boundingBox()
  const field = await page.getByLabel(fieldLabel, { exact: true }).boundingBox()
  if (!eye || !field) {
    throw new Error('password field not laid out')
  }
  return eye.x + eye.width / 2 > field.x + field.width / 2
}
