/**
 * Business currency, formatting locale and time zone (ADR-033, ADR-044). Starts with the seeded defaults
 * and is replaced by `GET /api/settings/public` once the user is logged in; components read them from here
 * (through `format.ts`), never hard-code them.
 */
export interface BusinessSettings {
  currency: string
  locale: string
  timeZone: string
}

let current: BusinessSettings = { currency: 'DZD', locale: 'fr-DZ', timeZone: 'Africa/Algiers' }

export function business(): BusinessSettings {
  return current
}

export function applyBusinessSettings(settings: BusinessSettings): void {
  current = { currency: settings.currency, locale: settings.locale, timeZone: settings.timeZone }
}
