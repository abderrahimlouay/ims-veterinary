/**
 * ADR-181: a document shows VAT only when at least one of its lines is taxed; a product not subject to VAT has rate 0.
 * The rate is a decimal string from the server; it is read only for its sign, never for an amount (BR-SALE-008).
 */
export function vatApplies(lines: readonly { vatRate: string }[]): boolean {
  return lines.some((line) => Number(line.vatRate) > 0)
}
