import { business } from './business'

// Decimal values from the API are strings (ADR-021). Intl.NumberFormat formats decimal strings exactly,
// so they are never converted to Number. Formatters follow the current business settings.
type DecimalString = string

/** `"1234.5"` → `"1 234,50 DA"`. */
export function formatMoney(amount: DecimalString): string {
  const { locale, currency } = business()
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount as Intl.StringNumericLiteral)
}

/** `"12.5"` → `"12,500"` (quantity scale 3, api.md §1). */
export function formatQuantity(value: DecimalString): string {
  return new Intl.NumberFormat(business().locale, { minimumFractionDigits: 3, maximumFractionDigits: 3 }).format(
    value as Intl.StringNumericLiteral,
  )
}

/** A UTC instant from the API shown in the business time zone. */
export function formatDateTime(instant: string): string {
  const { locale, timeZone } = business()
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(instant))
}
