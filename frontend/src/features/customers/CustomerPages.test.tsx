import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { i18n } from '../../lib/i18n'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'
import { creditLimitPayload, creditLimitState } from './creditLimit'

// Fictional data only (ADR-114): an invented name and a phone number of the reserved fake range (testing.md §7).
const NAME = 'Ferme Démo Atlas'
const PHONE = '0100 00 00 01'

const MANAGER = aUser({
  permissions: ['customer.read', 'customer.manage', 'customer.credit.manage', 'customer.price.manage', 'product.read'],
})
const READER = aUser({ permissions: ['customer.read'] })

function page(content: unknown[]) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

function aCustomer(overrides: Record<string, unknown> = {}) {
  return {
    id: 12,
    name: NAME,
    customerType: 'breeder',
    category: { id: 2, name: 'Éleveurs démo', discountPct: '5.00' },
    farmingType: 'Ovin',
    wilaya: 'Wilaya démo',
    commune: 'Commune démo',
    address: null,
    phone: PHONE,
    email: null,
    taxId: null,
    paymentTermsDays: 30,
    veterinarian: null,
    creditLimit: '0.00',
    active: true,
    createdAt: '2026-10-25T08:00:00Z',
    updatedAt: '2026-10-25T08:00:00Z',
    version: 3,
    ...overrides,
  }
}

const CATEGORIES = [{ id: 2, name: 'Éleveurs démo', discountPct: '5.00', updatedAt: '2026-10-25T08:00:00Z', version: 0 }]

function serveCustomer(customer: Record<string, unknown>) {
  server.use(
    http.get('/api/customers/12', () => HttpResponse.json(customer)),
    http.get('/api/customers', () => HttpResponse.json(page([]))),
    http.get('/api/customer-categories', () => HttpResponse.json(CATEGORIES)),
    http.get('/api/customers/12/prices', () => HttpResponse.json([])),
  )
}

let consoleCalls: MockInstance[] = []

beforeEach(() => {
  consoleCalls = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m))
})

afterEach(async () => {
  // ADR-114: no customer name or phone in console output (error reports are built from it).
  const printed = consoleCalls.flatMap((spy) => spy.mock.calls.map((args) => args.map(String).join(' '))).join('\n')
  expect(printed).not.toContain(NAME)
  expect(printed).not.toContain(PHONE)
  vi.restoreAllMocks()
  await i18n.changeLanguage('fr')
})

describe('credit-limit states (ADR-108)', () => {
  it('reads null as no limit, any zero as cash only, the rest as a limit', () => {
    expect(creditLimitState(null)).toBe('none')
    expect(creditLimitState('0.00')).toBe('cash')
    expect(creditLimitState('0')).toBe('cash')
    expect(creditLimitState('0.01')).toBe('limit')
    expect(creditLimitState('50000.00')).toBe('limit')
    expect(creditLimitPayload('none', '123')).toBeNull()
    expect(creditLimitPayload('cash', '123')).toBe('0.00')
    expect(creditLimitPayload('limit', '1500,5')).toBe('1500.5')
  })
})

describe('customers (frontend.md §4.6)', () => {
  it('lists customers and searches by phone in any format', async () => {
    serveMe(MANAGER)
    let query = ''
    server.use(
      http.get('/api/customers', ({ request }) => {
        query = new URL(request.url).search
        return HttpResponse.json(page([aCustomer()]))
      }),
    )
    renderApp('/customers')

    expect(await screen.findByRole('link', { name: NAME })).toHaveAttribute('href', '/customers/12')
    expect(screen.getByRole('link', { name: 'Nouveau client' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nom, téléphone ou NIF'), '+213100000001')
    await waitFor(() => expect(new URLSearchParams(query).get('q')).toBe('+213100000001'))
    expect(new URLSearchParams(query).get('active')).toBe('true')
  })

  it('warns about possible duplicates by ids only, then saves on confirmation; a new customer is cash only', async () => {
    serveMe(MANAGER)
    let probe: Record<string, unknown> = {}
    let created: Record<string, unknown> | undefined
    server.use(
      http.get('/api/customers', () => HttpResponse.json(page([]))),
      http.get('/api/customer-categories', () => HttpResponse.json(CATEGORIES)),
      http.post('/api/customers/similar', async ({ request }) => {
        probe = (await request.json()) as Record<string, unknown>
        return HttpResponse.json([{ customerId: 44, field: 'phone' }])
      }),
      http.post('/api/customers', async ({ request }) => {
        created = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aCustomer(), { status: 201 })
      }),
      http.get('/api/customers/12', () => HttpResponse.json(aCustomer())),
      http.get('/api/customers/12/prices', () => HttpResponse.json([])),
    )
    const router = renderApp('/customers/new')

    expect(await screen.findByText(/commence en « Comptant uniquement »/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nom'), NAME)
    await userEvent.type(screen.getByLabelText('Téléphone'), PHONE)
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    const warning = await screen.findByRole('alert')
    expect(within(warning).getByRole('link', { name: 'client n° 44' })).toHaveAttribute('href', '/customers/44')
    expect(probe).toEqual({ name: NAME, phone: PHONE, taxId: null, excludeId: null })
    expect(created).toBeUndefined()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer quand même' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/customers/12'))
    expect(created).not.toHaveProperty('creditLimit')
    expect(await screen.findByTestId('credit-limit')).toHaveTextContent('Comptant uniquement')
  })

  it('shows a duplicate refused by the server on the field it names', async () => {
    serveMe(MANAGER)
    server.use(
      http.get('/api/customers', () => HttpResponse.json(page([]))),
      http.get('/api/customer-categories', () => HttpResponse.json(CATEGORIES)),
      http.post('/api/customers/similar', () => HttpResponse.json([])),
      http.post('/api/customers', () => problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'taxId', customerId: 44 } })),
    )
    renderApp('/customers/new')

    await userEvent.type(await screen.findByLabelText('Nom'), NAME)
    await userEvent.type(screen.getByLabelText('NIF'), 'DEMO-NIF-0001')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Un autre client a déjà cette valeur.')).toBeInTheDocument()
    expect(screen.getByLabelText('NIF')).toHaveAttribute('aria-invalid', 'true')
  })
})

describe('credit-limit dialog (ADR-108), in fr, ar and en', () => {
  const CASES = [
    {
      lang: 'fr',
      dir: 'ltr',
      change: 'Modifier le plafond',
      none: 'Aucun plafond',
      cash: 'Comptant uniquement',
      limit: 'Plafond',
      amount: 'Montant du plafond',
      save: 'Enregistrer le plafond',
      limitShown: /^Plafond : 50\s000,00/,
    },
    {
      lang: 'ar',
      dir: 'rtl',
      change: 'تغيير سقف الائتمان',
      none: 'بدون سقف',
      cash: 'نقدًا فقط',
      limit: 'سقف',
      amount: 'مبلغ السقف',
      save: 'حفظ السقف',
      limitShown: /^السقف: /,
    },
    {
      lang: 'en',
      dir: 'ltr',
      change: 'Change the credit limit',
      none: 'No limit',
      cash: 'Cash only',
      limit: 'Limit',
      amount: 'Amount of the limit',
      save: 'Save the limit',
      limitShown: /^Limit: 50\s000,00/,
    },
  ]

  it.each(CASES)('$lang: shows each state and sends null, "0.00" or the amount explicitly', async (c) => {
    await i18n.changeLanguage(c.lang)
    serveMe(MANAGER)
    let current = aCustomer({ creditLimit: '0.00' })
    const sent: Record<string, unknown>[] = []
    serveCustomer(current)
    server.use(
      http.get('/api/customers/12', () => HttpResponse.json(current)),
      http.put('/api/customers/12/credit-limit', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        sent.push(body)
        current = aCustomer({ creditLimit: body.creditLimit, version: (body.version as number) + 1 })
        return HttpResponse.json(current)
      }),
    )
    renderApp('/customers/12')
    expect(document.documentElement.dir).toBe(c.dir)
    const shown = await screen.findByTestId('credit-limit')
    expect(shown).toHaveTextContent(c.cash)

    const choose = async (option: string, amount?: string) => {
      await userEvent.click(screen.getByRole('button', { name: c.change }))
      const dialog = await screen.findByRole('dialog')
      await userEvent.click(within(dialog).getByRole('radio', { name: new RegExp(`^${option}`) }))
      if (amount !== undefined) {
        const field = within(dialog).getByLabelText(c.amount)
        await userEvent.clear(field)
        await userEvent.type(field, amount)
      } else {
        expect(within(dialog).queryByLabelText(c.amount)).not.toBeInTheDocument()
      }
      await userEvent.click(within(dialog).getByRole('button', { name: c.save }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    }

    await choose(c.none)
    expect(sent[0]).toHaveProperty('creditLimit', null)
    expect(sent[0]).toEqual({ creditLimit: null, version: 3 })
    await waitFor(() => expect(screen.getByTestId('credit-limit')).toHaveTextContent(c.none))

    await choose(c.limit, '50000.00')
    expect(sent[1]).toEqual({ creditLimit: '50000.00', version: 4 })
    await waitFor(() => expect(screen.getByTestId('credit-limit').textContent).toMatch(c.limitShown))

    await choose(c.cash)
    expect(sent[2]).toEqual({ creditLimit: '0.00', version: 5 })
    await waitFor(() => expect(screen.getByTestId('credit-limit')).toHaveTextContent(c.cash))
  })

  it('refuses a zero typed as a limit: that is cash only', async () => {
    serveMe(MANAGER)
    const sent: unknown[] = []
    serveCustomer(aCustomer({ creditLimit: null }))
    server.use(
      http.put('/api/customers/12/credit-limit', async ({ request }) => {
        sent.push(await request.json())
        return HttpResponse.json(aCustomer())
      }),
    )
    renderApp('/customers/12')

    expect(await screen.findByTestId('credit-limit')).toHaveTextContent('Aucun plafond')
    await userEvent.click(screen.getByRole('button', { name: 'Modifier le plafond' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('radio', { name: /^Aucun plafond/ })).toBeChecked()
    await userEvent.click(within(dialog).getByRole('radio', { name: /^Plafond/ }))
    await userEvent.type(within(dialog).getByLabelText('Montant du plafond'), '0')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer le plafond' }))

    expect(await within(dialog).findByText(/choisissez cette option/)).toBeInTheDocument()
    expect(sent).toEqual([])
  })
})

describe('permissions: the controls appear only with their permission (BR-SEC-002)', () => {
  it('a reader sees the record, the limit and the prices, with no control', async () => {
    serveMe(READER)
    serveCustomer(aCustomer({ creditLimit: '120000.00' }))
    server.use(
      http.get('/api/customers/12/prices', () =>
        HttpResponse.json([{ product: { id: 5, sku: 'DEMO-1', commercialName: 'Produit démo', baseUnit: 'tablet', archived: false }, unitPrice: '0.4000', updatedAt: '2026-10-25T08:00:00Z', version: 0 }]),
      ),
    )
    renderApp('/customers/12')

    expect(await screen.findByTestId('credit-limit')).toHaveTextContent(/^Plafond : 120\s000,00/)
    expect(screen.queryByRole('button', { name: 'Modifier le plafond' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Désactiver' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Prix' }))
    expect(await screen.findByText('Produit démo')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Modifier le prix/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Supprimer le prix/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Ajouter un prix')).not.toBeInTheDocument()
  })

  it('without customer.credit.manage, a manager edits the record but not the limit', async () => {
    serveMe(aUser({ permissions: ['customer.read', 'customer.manage'] }))
    serveCustomer(aCustomer())
    renderApp('/customers/12')

    expect(await screen.findByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Modifier le plafond' })).not.toBeInTheDocument()
  })
})

describe('prices tab (ADR-111)', () => {
  it('sets, changes and removes one price at a time with its version', async () => {
    serveMe(MANAGER)
    serveCustomer(aCustomer())
    const calls: string[] = []
    let prices = [{ product: { id: 5, sku: 'DEMO-1', commercialName: 'Produit démo', baseUnit: 'tablet', archived: false }, unitPrice: '0.4000', updatedAt: '2026-10-25T08:00:00Z', version: 2 }]
    server.use(
      http.get('/api/customers/12/prices', () => HttpResponse.json(prices)),
      http.get('/api/products', () =>
        HttpResponse.json(page([{ id: 6, sku: 'DEMO-2', commercialName: 'Autre démo', baseUnit: 'ml', category: null, salePrice: '1.00', archived: false, sellableQtyBase: '0.000', totalQtyBase: '0.000', stockStatus: 'out', nearestExpiry: null }])),
      ),
      http.put('/api/customers/12/prices/:productId', async ({ request, params }) => {
        calls.push(`PUT ${String(params.productId)} ${JSON.stringify(await request.json())}`)
        return HttpResponse.json(prices[0])
      }),
      http.delete('/api/customers/12/prices/:productId', ({ request, params }) => {
        calls.push(`DELETE ${String(params.productId)} ${new URL(request.url).search}`)
        prices = []
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp('/customers/12')

    await userEvent.click(await screen.findByRole('tab', { name: 'Prix' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier le prix de Produit démo' }))
    const field = screen.getByLabelText('Prix par unité de base')
    await userEvent.clear(field)
    await userEvent.type(field, '0,38')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer le prix' }))
    await waitFor(() => expect(calls).toContain('PUT 5 {"unitPrice":"0.38","version":2}'))

    await userEvent.type(screen.getByLabelText('Chercher un produit'), 'Autre')
    await userEvent.click(await screen.findByRole('button', { name: /Autre démo/ }))
    await userEvent.type(screen.getByLabelText('Prix par unité de base'), '0.9')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer le prix' }))
    await waitFor(() => expect(calls).toContain('PUT 6 {"unitPrice":"0.9","version":null}'))

    await userEvent.click(screen.getByRole('button', { name: 'Supprimer le prix de Produit démo' }))
    await waitFor(() => expect(calls).toContain('DELETE 5 ?version=2'))
    expect(await screen.findByText('Aucun prix propre.')).toBeInTheDocument()
  })
})

describe('customer categories (ADR-112)', () => {
  it('lists, adds and edits with customer.price.manage only', async () => {
    serveMe(MANAGER)
    const sent: unknown[] = []
    server.use(
      http.get('/api/customer-categories', () => HttpResponse.json(CATEGORIES)),
      http.post('/api/customer-categories', async ({ request }) => {
        sent.push(await request.json())
        return HttpResponse.json({ ...CATEGORIES[0], id: 3 }, { status: 201 })
      }),
      http.put('/api/customer-categories/2', async ({ request }) => {
        sent.push(await request.json())
        return HttpResponse.json(CATEGORIES[0])
      }),
    )
    renderApp('/customer-categories')

    expect(await screen.findByText('Éleveurs démo')).toBeInTheDocument()
    expect(screen.getByText('remise 5.00 %')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Modifier Éleveurs démo' }))
    const discount = screen.getAllByLabelText('Remise (%)')[0]
    await userEvent.clear(discount)
    await userEvent.type(discount, '7,5')
    await userEvent.click(screen.getAllByRole('button', { name: 'Enregistrer' })[0])
    await waitFor(() => expect(sent).toContainEqual({ name: 'Éleveurs démo', discountPct: '7.5', version: 0 }))
  })

  it('a reader has no control', async () => {
    serveMe(READER)
    server.use(http.get('/api/customer-categories', () => HttpResponse.json(CATEGORIES)))
    renderApp('/customer-categories')

    expect(await screen.findByText('Éleveurs démo')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText('Nouvelle catégorie')).not.toBeInTheDocument()
  })
})
