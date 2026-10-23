// Decimal strings from the API (ADR-021) are never turned into floating-point numbers. These helpers only serve
// previews; the server computes every stored value (BR-SALE-008).

/** `"12.5"` at scale 3 → `12500n`; `null` when the text is not a plain decimal with at most `scale` decimals. */
function toScaled(value: string, scale: number): bigint | null {
  const match = /^(-?)(\d+)(?:[.,](\d+))?$/.exec(value.trim())
  if (!match || (match[3] ?? '').length > scale) {
    return null
  }
  const [, sign, whole, fraction = ''] = match
  const scaled = BigInt(whole + fraction.padEnd(scale, '0'))
  return sign === '-' ? -scaled : scaled
}

function fromScaled(value: bigint, scale: number): string {
  const negative = value < 0n
  const digits = (negative ? -value : value).toString().padStart(scale + 1, '0')
  return `${negative ? '-' : ''}${digits.slice(0, -scale)}.${digits.slice(-scale)}`
}

/** `a − b` exactly at `scale` decimals (default 3, base quantities); `null` when either is not a decimal. */
export function subtractDecimal(a: string, b: string, scale = 3): string | null {
  const left = toScaled(a, scale)
  const right = toScaled(b, scale)
  return left === null || right === null ? null : fromScaled(left - right, scale)
}

/** Whether the decimal string is below zero. */
export function isNegativeDecimal(value: string): boolean {
  return value.trim().startsWith('-') && /[1-9]/.test(value)
}
