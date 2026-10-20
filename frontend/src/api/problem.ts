/** A field error of VALIDATION_FAILED (api.md §2). */
export interface FieldError {
  field: string
  code: string
  message: string
}

/**
 * The client's view of every failed request: RFC 9457 Problem Details plus the business code
 * (ADR-020). The UI switches on `code`, never on `detail` text.
 */
export interface Problem {
  /** HTTP status; 0 when the server could not be reached. */
  status: number
  code: string
  title?: string
  detail?: string
  traceId?: string
  errors: FieldError[]
  context: Record<string, unknown>
}

function codeForStatus(status: number): string {
  switch (status) {
    case 401:
      return 'AUTH_REQUIRED'
    case 403:
      return 'ACCESS_DENIED'
    case 404:
      return 'NOT_FOUND'
    case 405:
      return 'METHOD_NOT_ALLOWED'
    case 415:
      return 'MEDIA_TYPE_NOT_SUPPORTED'
    case 429:
      return 'RATE_LIMITED'
    default:
      return status >= 400 && status < 500 ? 'VALIDATION_FAILED' : 'INTERNAL_ERROR'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

/** Builds a {@link Problem} from any error response, whether or not its body is Problem Details. */
export function normaliseProblem(status: number, headers: Headers, body: unknown): Problem {
  const pd = isRecord(body) ? body : {}
  return {
    status,
    code: optionalString(pd.code) ?? codeForStatus(status),
    title: optionalString(pd.title),
    detail: optionalString(pd.detail),
    traceId: optionalString(pd.traceId) ?? headers.get('X-Trace-Id') ?? undefined,
    errors: Array.isArray(pd.errors) ? (pd.errors as FieldError[]) : [],
    context: isRecord(pd.context) ? pd.context : {},
  }
}

/** The server could not be reached (frontend.md §9: 5xx / network). */
export function networkProblem(): Problem {
  return { status: 0, code: 'NETWORK_ERROR', errors: [], context: {} }
}
