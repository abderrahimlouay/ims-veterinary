import { business } from './business'

/**
 * An expiry typed as `AAAA-MM-JJ`, `JJ/MM/AAAA` or a month `MM/AAAA` (ADR-055: a month is its last day) → the ISO
 * date sent to the API, or `null` when it is not a real date.
 */
export function parseExpiry(text: string): string | null {
  const value = text.trim()
  let year: number
  let month: number
  let day: number | undefined
  let match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match) {
    ;[year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])]
  } else if ((match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(value))) {
    ;[day, month, year] = [Number(match[1]), Number(match[2]), Number(match[3])]
  } else if ((match = /^(\d{1,2})\/(\d{4})$/.exec(value))) {
    ;[month, year] = [Number(match[1]), Number(match[2])]
  } else {
    return null
  }
  if (month < 1 || month > 12) {
    return null
  }
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const d = day ?? lastDay
  if (d < 1 || d > lastDay) {
    return null
  }
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

/** Today's date in the business time zone (ADR-033), as `AAAA-MM-JJ`. */
export function businessToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: business().timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}
