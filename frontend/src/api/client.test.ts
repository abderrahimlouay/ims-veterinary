import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '../test/server'
import { ApiError, apiFetch } from './client'

describe('apiFetch', () => {
  it('returns parsed JSON and keeps decimals as strings', async () => {
    server.use(http.get('/api/prices/1', () => HttpResponse.json({ id: 1, price: '1234.50' })))

    const body = await apiFetch<{ id: number; price: string }>('/api/prices/1')

    expect(body).toEqual({ id: 1, price: '1234.50' })
  })

  it('sends JSON bodies with the right headers', async () => {
    let seen: { contentType: string | null; accept: string | null; body: unknown } | undefined
    server.use(
      http.post('/api/echo', async ({ request }) => {
        seen = {
          contentType: request.headers.get('Content-Type'),
          accept: request.headers.get('Accept'),
          body: await request.json(),
        }
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const result = await apiFetch('/api/echo', { method: 'POST', json: { quantity: '2.500' } })

    expect(result).toBeUndefined()
    expect(seen).toEqual({
      contentType: 'application/json',
      accept: 'application/json, application/problem+json',
      body: { quantity: '2.500' },
    })
  })

  it('throws an ApiError carrying the normalised problem', async () => {
    server.use(
      http.get('/api/lots/9', () =>
        HttpResponse.json(
          { status: 422, code: 'LOT_EXPIRED', title: 'Lot expired', detail: 'Lot L1 expired.', traceId: 't1' },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await apiFetch('/api/lots/9').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).problem).toMatchObject({ status: 422, code: 'LOT_EXPIRED', traceId: 't1' })
  })

  it('turns a network failure into NETWORK_ERROR', async () => {
    server.use(http.get('/api/down', () => HttpResponse.error()))

    const error = await apiFetch('/api/down').catch((e: unknown) => e)

    expect((error as ApiError).problem.code).toBe('NETWORK_ERROR')
  })

  describe('CSRF (ADR-036)', () => {
    afterEach(() => {
      document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
    })

    it('sends the token from the cookie on state-changing requests only', async () => {
      document.cookie = 'XSRF-TOKEN=tok-123; path=/'
      const seen: Record<string, string | null> = {}
      server.use(
        http.get('/api/things', ({ request }) => {
          seen.get = request.headers.get('X-XSRF-TOKEN')
          return HttpResponse.json([])
        }),
        http.put('/api/things/1', ({ request }) => {
          seen.put = request.headers.get('X-XSRF-TOKEN')
          return new HttpResponse(null, { status: 204 })
        }),
      )

      await apiFetch('/api/things')
      await apiFetch('/api/things/1', { method: 'PUT', json: {} })

      expect(seen).toEqual({ get: null, put: 'tok-123' })
    })

    it('obtains the cookie first when it is missing', async () => {
      let header: string | null = null
      server.use(
        http.get('/api/auth/me', () => {
          document.cookie = 'XSRF-TOKEN=fresh-456; path=/'
          return HttpResponse.json({}, { status: 401 })
        }),
        http.post('/api/auth/login', ({ request }) => {
          header = request.headers.get('X-XSRF-TOKEN')
          return HttpResponse.json({})
        }),
      )

      await apiFetch('/api/auth/login', { method: 'POST', json: {} })

      expect(header).toBe('fresh-456')
    })
  })
})
