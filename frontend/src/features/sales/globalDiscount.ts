import type { TFunction } from 'i18next'
import { formatMoney, formatRate } from '../../lib/format'
import type { GlobalDiscount } from './saleApi'

/** "Remise globale (10 %)" for a percentage, "Remise globale" for an amount (ADR-175). */
export function globalDiscountLabel(discount: GlobalDiscount, t: TFunction): string {
  return discount.kind === 'percent' ? t('pos.globalDiscountOf', { rate: formatRate(discount.value) }) : t('pos.globalDiscount')
}

/** What the global discount took off, as a deduction: "-2 000,00 DA", or "0,00 DA" when it rounds to nothing. */
export function deduction(amount: string): string {
  return Number(amount) > 0 ? `-${formatMoney(amount)}` : formatMoney(amount)
}

/** The value as typed in the form: "10.00" reads "10", "2.50" reads "2.5". */
export function editableValue(value: string): string {
  return value.includes('.') ? value.replace(/\.?0+$/, '') : value
}
