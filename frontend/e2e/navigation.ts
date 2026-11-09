import type { Locator, Page } from '@playwright/test'

/**
 * The side navigation with every section open (frontend.md §3): a closed section's links are hidden, and the sections
 * a user opens stay open on the device, so later calls find them open.
 */
export async function sidebar(page: Page): Promise<Locator> {
  const nav = page.getByRole('navigation', { name: 'Navigation principale' })
  await nav.waitFor()
  // Opening a section takes it out of the closed ones: always open the first that is left.
  const closed = nav.locator('button[aria-expanded="false"]')
  while ((await closed.count()) > 0) {
    await closed.first().click()
  }
  return nav
}
