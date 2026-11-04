import { expect, test, type Page } from '@playwright/test'
import { ADMIN, CASHIER } from './fixtures'
import { changePassword, logIn, logOut } from './session'

/**
 * J10 (testing.md §8, Phase 9): on the administrator's own cash session, a manual cash in and a manual cash out with
 * their labels; a supplier invoice recorded and paid in cash from the supplier's Payables tab; the session report by
 * source; then a Seller opens a session at the counter and the administrator (`cash.manage`) closes it with its
 * variance (ADR-132, ADR-133, ADR-136, BR-CASH-002/003). Runs in its own `cash` project after `payments`, as it uses the
 * administrator's session. Fictional data only (ADR-114).
 */
test.describe.configure({ mode: 'serial', retries: 0 })

const SUPPLIER = 'E2E Fournisseur Caisse'

async function movement(page: Page, direction: 'in' | 'out', amount: string, label: string) {
  await page.getByRole('button', { name: 'Mouvement manuel' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Sens').selectOption(direction)
  await dialog.getByLabel('Montant').fill(amount)
  await dialog.getByLabel('Libellé').fill(label)
  await dialog.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(dialog).toBeHidden()
}

test('J10: manual movements, a supplier paid in cash, the session report, a manager closing another session', async ({ page }) => {
  await page.goto('/login')
  await logIn(page, ADMIN.username, ADMIN.newPassword)
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()

  // The administrator's own session: J4 opened it; open one if it is not there.
  await page.goto('/pos')
  const bar = page.getByTestId('cash-session')
  await expect(bar.getByRole('button')).toBeVisible()
  if (await bar.getByRole('button', { name: 'Ouvrir la session' }).isVisible()) {
    await bar.getByRole('button', { name: 'Ouvrir la session' }).click()
    await page.getByRole('dialog').getByLabel('Fonds de caisse').fill('0')
    await page.getByRole('dialog').getByRole('button', { name: 'Ouvrir la session' }).click()
    await expect(bar.getByRole('button', { name: 'Fermer la session' })).toBeVisible()
  }

  await page.goto('/')
  await nav.getByRole('link', { name: 'Sessions de caisse' }).click()
  await movement(page, 'in', '100', 'Apport J10')
  await movement(page, 'out', '20', 'Dépôt banque J10')

  await nav.getByRole('link', { name: 'Fournisseurs', exact: true }).click()
  await page.getByRole('link', { name: 'Nouveau fournisseur' }).click()
  await page.getByLabel('Nom', { exact: true }).fill(SUPPLIER)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: SUPPLIER })).toBeVisible()
  await page.getByRole('tab', { name: 'Dettes' }).click()
  await expect(page.getByTestId('supplier-balance')).toHaveText(/^0,00/)
  await page.getByRole('link', { name: 'Saisir une facture' }).click()
  await expect(page.getByLabel('Fournisseur', { exact: true })).toHaveValue(/\d+/)
  await page.getByLabel('N° fournisseur').fill('J10-F1')
  await page.getByLabel('Date de facture').fill('2026-11-01')
  await page.getByLabel('Total TTC').fill('30')
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Facture J10-F1' })).toBeVisible()
  await expect(page.getByTestId('invoice-open')).toHaveText(/^30,00/)

  await page.getByRole('link', { name: SUPPLIER }).click()
  await page.getByRole('tab', { name: 'Dettes' }).click()
  await expect(page.getByTestId('payable-invoice')).toHaveCount(1)
  await page.getByRole('button', { name: 'Enregistrer un paiement' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Mode de paiement').selectOption('cash')
  await dialog.getByLabel('Montant').fill('30')
  await dialog.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('Aucune facture ouverte.')).toBeVisible()
  await expect(page.getByTestId('supplier-balance')).toHaveText(/^0,00/)
  await expect(page.getByTestId('supplier-payment')).toHaveCount(1)

  await nav.getByRole('link', { name: 'Sessions de caisse' }).click()
  await page.getByRole('link', { name: 'Rapport de ma session' }).click()
  await expect(page.getByTestId('source-manual')).toContainText('100,00')
  await expect(page.getByTestId('source-manual')).toContainText('20,00')
  await expect(page.getByTestId('source-supplier_payments')).toContainText('30,00')

  // A Seller opens a session at the counter; the administrator closes it.
  await nav.getByRole('link', { name: 'Utilisateurs' }).click()
  await page.getByRole('link', { name: 'Nouvel utilisateur' }).click()
  await page.getByLabel("Nom d'utilisateur").fill(CASHIER.username)
  await page.getByLabel('Nom complet').fill(CASHIER.fullName)
  await page.getByLabel('Rôle').selectOption({ label: 'Seller' })
  await page.getByLabel('Mot de passe initial').fill(CASHIER.initialPassword)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(page.getByRole('heading', { level: 1, name: CASHIER.username })).toBeVisible()
  await logOut(page)

  await logIn(page, CASHIER.username, CASHIER.initialPassword)
  await expect(page).toHaveURL(/\/account$/)
  await changePassword(page, CASHIER.initialPassword, CASHIER.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Sessions de caisse' })).toHaveCount(0)
  await page.goto('/pos')
  await bar.getByRole('button', { name: 'Ouvrir la session' }).click()
  await page.getByRole('dialog').getByLabel('Fonds de caisse').fill('50')
  await page.getByRole('dialog').getByRole('button', { name: 'Ouvrir la session' }).click()
  await expect(bar.getByRole('button', { name: 'Fermer la session' })).toBeVisible()
  await page.goto('/cash-sessions')
  await expect(page.getByRole('heading', { level: 1, name: 'Accès refusé' })).toBeVisible()
  await page.goto('/')
  await logOut(page)

  await logIn(page, ADMIN.username, ADMIN.newPassword)
  await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  await nav.getByRole('link', { name: 'Sessions de caisse' }).click()
  const theirs = page.getByTestId('cash-session-row').filter({ hasNotText: 'Vous' }).filter({ hasText: 'Ouverte' })
  await expect(theirs).toHaveCount(1)
  const number = (await theirs.getByRole('link').textContent()) ?? ''
  await theirs.getByRole('button', { name: 'Fermer la session' }).click()
  await page.getByRole('dialog').getByLabel('Montant compté').fill('45')
  await page.getByRole('dialog').getByRole('button', { name: 'Fermer la session' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  const closed = page.getByTestId('cash-session-row').filter({ has: page.getByRole('link', { name: number, exact: true }) })
  await expect(closed).toContainText('-5,00')
  await closed.getByRole('link').click()
  await expect(page.getByTestId('session-status')).toHaveText('Fermée')
  await expect(page.getByTestId('report-variance')).toContainText('-5,00')
  await expect(page.getByText('par Vous')).toBeVisible()
})
