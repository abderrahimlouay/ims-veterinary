import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
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
})
