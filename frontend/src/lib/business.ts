/**
 * Business currency, formatting locale and time zone (ADR-033). Defaults until the public settings
 * endpoint exists (Phase 1); components must read them from here, never hard-code them.
 */
export const business = {
  currency: 'DZD',
  locale: 'fr-DZ',
  timeZone: 'Africa/Algiers',
} as const
