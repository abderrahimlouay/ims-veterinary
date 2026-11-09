/** Calendar arithmetic on business dates `AAAA-MM-JJ` (ADR-033), in UTC so no time zone shifts a day. */
function toUtc(day: string): Date {
  return new Date(Date.UTC(Number(day.substring(0, 4)), Number(day.substring(5, 7)) - 1, Number(day.substring(8, 10))))
}

function fromUtc(date: Date): string {
  return date.toISOString().substring(0, 10)
}

export function addDays(day: string, days: number): string {
  const date = toUtc(day)
  date.setUTCDate(date.getUTCDate() + days)
  return fromUtc(date)
}

/** The first day of the month `months` before (negative) or after the month of `day`. */
export function firstOfMonth(day: string, months = 0): string {
  const date = toUtc(day)
  return fromUtc(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + months, 1)))
}

/** Every day from `from` to `to`, both included. */
export function daysBetween(from: string, to: string): string[] {
  const days: string[] = []
  for (let day = from; day <= to; day = addDays(day, 1)) {
    days.push(day)
  }
  return days
}

/** Every month `AAAA-MM` from the month of `from` to that of `to`. */
export function monthsBetween(from: string, to: string): string[] {
  const months: string[] = []
  for (let day = firstOfMonth(from); day.substring(0, 7) <= to.substring(0, 7); day = firstOfMonth(day, 1)) {
    months.push(day.substring(0, 7))
  }
  return months
}
