import { networkProblem, normaliseProblem, type Problem } from './problem'

/** Every failed API call rejects with this error; read `problem.code` to react. */
export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.code)
    this.name = 'ApiError'
    this.problem = problem
  }
}

export type ApiInit = Omit<RequestInit, 'body'> & {
  /** Serialised as the JSON request body. Decimals must already be strings (ADR-021). */
  json?: unknown
}

/** ADR-036: the CSRF token arrives in a readable cookie and goes back in a header on state changes. */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
const SAFE_METHODS: ReadonlySet<string> = new Set(['GET', 'HEAD', 'OPTIONS'])

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const cookie = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice(prefix.length))
}

/** Any response sets the CSRF cookie; on a first visit a harmless GET obtains it before a state change. */
async function csrfToken(): Promise<string | undefined> {
  const existing = readCookie(CSRF_COOKIE)
  if (existing !== undefined) {
    return existing
  }
  try {
    await fetch(new URL('/api/auth/me', window.location.origin), { headers: { Accept: 'application/json' } })
  } catch {
    return undefined
  }
  return readCookie(CSRF_COOKIE)
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text()
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/**
 * The single typed fetch wrapper for `/api` (frontend.md §1). Resolves with the parsed JSON body
 * (`undefined` for 204) or rejects with an {@link ApiError}.
 */
export async function apiFetch<T = void>(path: string, init: ApiInit = {}): Promise<T> {
  const { json, headers, ...rest } = init
  const requestHeaders = new Headers(headers)
  requestHeaders.set('Accept', 'application/json, application/problem+json')
  if (!SAFE_METHODS.has((rest.method ?? 'GET').toUpperCase())) {
    const token = await csrfToken()
    if (token !== undefined) {
      requestHeaders.set(CSRF_HEADER, token)
    }
  }
  let body: string | undefined
  if (json !== undefined) {
    requestHeaders.set('Content-Type', 'application/json')
    body = JSON.stringify(json)
  }

  let response: Response
  try {
    response = await fetch(new URL(path, window.location.origin), { ...rest, headers: requestHeaders, body })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(networkProblem())
  }

  if (!response.ok) {
    throw new ApiError(normaliseProblem(response.status, response.headers, await readBody(response)))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
