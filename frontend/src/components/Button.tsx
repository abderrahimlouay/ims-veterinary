import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'secondary'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand text-brand-ink hover:bg-brand/90',
  secondary: 'bg-surface text-ink border border-line hover:bg-bg',
}

export function Button({
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      {...props}
      className={`h-10 px-4 rounded-ctl text-sm font-medium focus:outline-2 focus:outline-offset-2 focus:outline-brand disabled:opacity-60 ${VARIANTS[variant]} ${className}`}
    />
  )
}
