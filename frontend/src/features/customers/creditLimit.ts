/**
 * The three credit-limit states of ADR-108, never confused: `null` is "no limit", `"0.00"` (any zero) is "cash
 * only", anything else is a limit. A new customer starts at cash only (ADR-109).
 */
export type CreditLimitState = 'none' | 'cash' | 'limit'

export function creditLimitState(limit: string | null): CreditLimitState {
  if (limit === null) {
    return 'none'
  }
  return /^0*(\.0*)?$/.test(limit.trim()) ? 'cash' : 'limit'
}

/** The value sent for a state: `null` for no limit, `"0.00"` for cash only, the typed amount for a limit. */
export function creditLimitPayload(state: CreditLimitState, amount: string): string | null {
  if (state === 'none') {
    return null
  }
  return state === 'cash' ? '0.00' : amount.trim().replace(',', '.')
}
