import type { TFunction } from 'i18next'
import { formatRate } from '../../lib/format'
import type { GlobalDiscount } from './saleApi'

/** "Remise globale (10 %)" for a percentage, "Remise globale" for an amount (ADR-175). */
export function globalDiscountLabel(discount: GlobalDiscount, t: TFunction): string {
  return discount.kind === 'percent' ? t('pos.globalDiscountOf', { rate: formatRate(discount.value) }) : t('pos.globalDiscount')
}
