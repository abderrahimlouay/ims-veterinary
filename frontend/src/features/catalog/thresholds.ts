import { isNegativeDecimal, subtractDecimal } from '../../lib/decimal'

/**
 * ADR-092 (OD-034 B): a max stock at or below a reorder point > 0 is accepted but warned about, because no reorder
 * proposal can be generated for it (reported "thresholds inconsistent", ADR-077 amendment). Nothing when the max
 * is empty, the reorder point is 0, or either value is not a decimal yet.
 */
export function thresholdsInconsistent(maxStock: string, reorderPoint: string): boolean {
  if (maxStock.trim() === '' || !/[1-9]/.test(reorderPoint)) {
    return false
  }
  const margin = subtractDecimal(maxStock, reorderPoint)
  return margin !== null && (isNegativeDecimal(margin) || !/[1-9]/.test(margin))
}
