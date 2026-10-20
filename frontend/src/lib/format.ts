import { business } from './business'

// Decimal values from the API are strings (ADR-021). Intl.NumberFormat formats decimal strings exactly,
// so they are never converted to Number.
type DecimalString = string

const money = new Intl.NumberFormat(business.locale, { style: 'currency', currency: business.currency })

const quantity = new Intl.NumberFormat(business.locale, { minimumFractionDigits: 3, maximumFractionDigits: 3 })

const dateTime = new Intl.DateTimeFormat(business.locale, {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: business.timeZone,
})

/** `"1234.5"` → `"1 234,50 DA"`. */
export function formatMoney(amount: DecimalString): string {
  return money.format(amount as Intl.StringNumericLiteral)
}

/** `"12.5"` → `"12,500"` (quantity scale 3, api.md §1). */
export function formatQuantity(value: DecimalString): string {
  return quantity.format(value as Intl.StringNumericLiteral)
}

/** A UTC instant from the API shown in the business time zone. */
export function formatDateTime(instant: string): string {
  return dateTime.format(new Date(instant))
}
