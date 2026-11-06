import { expect, test, type Page } from '@playwright/test'

/**
 * J16 (ADR-069, ADR-162, ADR-163): the theme switch forces dark or light, the choice survives a reload, and the saved
 * theme is applied by `theme-init.js` before the application runs, so the wrong theme never flashes. The application
 * bundle is blocked on one reload to prove it. Uses the login page only; independent of the other journeys.
 */
const DARK_BG = 'rgb(12, 20, 23)'
const LIGHT_BG = 'rgb(246, 248, 248)'

const theme = (page: Page) => page.locator('html').getAttribute('data-theme')
const background = (page: Page) => page.evaluate(() => getComputedStyle(document.body).backgroundColor)
const choice = (page: Page, name: string) => page.getByRole('radiogroup', { name: 'Thème' }).getByText(name, { exact: true })

test.use({ colorScheme: 'light' })

test('J16: the chosen theme applies at once, survives a reload and never flashes', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('radio', { name: 'Système' })).toBeChecked()
  expect(await theme(page)).toBeNull()
  expect(await background(page)).toBe(LIGHT_BG)

  await choice(page, 'Sombre').click()
  await expect(page.getByRole('radio', { name: 'Sombre' })).toBeChecked()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(await background(page)).toBe(DARK_BG)
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('dark')

  // Without the application's code, only theme-init.js can have set the theme: it is in place before React runs.
  await page.route('**/assets/*.js', (route) => route.abort())
  await page.reload()
  await expect(page.locator('#root')).toBeEmpty()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(await background(page)).toBe(DARK_BG)
  await page.unroute('**/assets/*.js')

  await page.reload()
  await expect(page.getByRole('radio', { name: 'Sombre' })).toBeChecked()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

  await choice(page, 'Clair').click()
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.reload()
  await expect(page.getByRole('radio', { name: 'Clair' })).toBeChecked()
  expect(await background(page)).toBe(LIGHT_BG)

  await choice(page, 'Système').click()
  expect(await theme(page)).toBeNull()
  expect(await background(page)).toBe(DARK_BG)
  expect(await page.evaluate(() => localStorage.getItem('ims.theme'))).toBeNull()
})
