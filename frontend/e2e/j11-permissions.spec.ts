import { expect, test } from '@playwright/test'
import { ADMIN, SELLER } from './fixtures'
import { changePassword, logIn, logOut } from './session'

/**
 * J11 (testing.md §8): the administrator creates a Seller; the Seller has no administration entries and
 * sees the forbidden state on administration pages; the creation is in the audit log under the
 * administrator's name (ADR-046). Runs after J1; stateful, so serial and never retried.
 */
test.describe.configure({ mode: 'serial', retries: 0 })

test('J11: a restricted user sees forbidden states; the administrator sees the audit trail', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await expect(nav.getByRole('link', { name: 'Utilisateurs' })).toBeVisible()
  await expect(nav.getByRole('link', { name: "Journal d'audit" })).toBeVisible()

  await nav.getByRole('link', { name: 'Utilisateurs' }).click()
  await page.getByRole('link', { name: 'Nouvel utilisateur' }).click()
  await page.getByLabel("Nom d'utilisateur").fill(SELLER.username)
  await page.getByLabel('Nom complet').fill(SELLER.fullName)
  await page.getByLabel('Rôle').selectOption({ label: 'Seller' })
  await page.getByLabel('Mot de passe initial').fill(SELLER.initialPassword)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: SELLER.username })).toBeVisible()
  await logOut(page)

  await logIn(page, SELLER.username, SELLER.initialPassword)
  await expect(page).toHaveURL(/\/account$/)
  await changePassword(page, SELLER.initialPassword, SELLER.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Utilisateurs' })).toHaveCount(0)
  await expect(nav.getByRole('link', { name: "Journal d'audit" })).toHaveCount(0)
  for (const path of ['/admin/users', '/admin/roles', '/admin/settings', '/admin/audit']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1, name: 'Accès refusé' })).toBeVisible()
  }
  await logOut(page)

  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await nav.getByRole('link', { name: "Journal d'audit" }).click()
  await page.getByLabel('Action').selectOption('USER_CREATE')
  const created = page.getByRole('row').filter({ hasText: 'e2e.admin (E2E Administrator)' })
  await expect(created).toHaveCount(1)
  await created.getByRole('button', { name: 'Détails' }).click()
  await expect(page.getByRole('complementary', { name: 'Détails' })).toContainText(SELLER.username)
})
