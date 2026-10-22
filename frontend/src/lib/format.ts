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

/**
 * A UTC instant from the API shown in the business time zone, on a 24-hour clock whatever the locale's
 * default (`fr-DZ` data uses AM/PM; user decision 2026-10-21, ADR-033).
 */
export function formatDateTime(instant: string): string {
  const { locale, timeZone } = business()
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short', hourCycle: 'h23', timeZone }).format(
    new Date(instant),
  )
}

const unitPrice = () =>
  new Intl.NumberFormat(business().locale, {
    style: 'currency',
    currency: business().currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  })

/** A price per base unit (4 decimals, ADR-049): `"0.0125"` → `"0,0125 DA"`, `"12.5000"` → `"12,50 DA"`. */
export function formatUnitPrice(price: DecimalString): string {
  return unitPrice().format(price as Intl.StringNumericLiteral)
}

/** A unit factor (4 decimals at most, ADR-065) without trailing zeros: `"30.0000"` → `"30"`, `"2.5000"` → `"2,5"`. */
export function formatFactor(factor: DecimalString): string {
  return new Intl.NumberFormat(business().locale, { maximumFractionDigits: 4, useGrouping: false }).format(
    factor as Intl.StringNumericLiteral,
  )
}
