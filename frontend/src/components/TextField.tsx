import { forwardRef, useId, type InputHTMLAttributes } from 'react'

type Props = InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }

/** Labelled input; the hint and the error are announced with the field (aria-describedby). */
export const TextField = forwardRef<HTMLInputElement, Props>(function TextField({ label, error, hint, ...input }, ref) {
  const id = useId()
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy = [hint !== undefined ? hintId : undefined, error !== undefined ? errorId : undefined].filter(Boolean).join(' ')
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error !== undefined}
        aria-describedby={describedBy === '' ? undefined : describedBy}
        {...input}
        className="h-10 px-3 rounded-ctl bg-surface border border-control text-sm focus:outline-2 focus:outline-brand aria-invalid:border-danger"
      />
      {hint !== undefined && (
        <p id={hintId} className="text-xs text-mute">
          {hint}
        </p>
      )}
      {error !== undefined && (
        <p id={errorId} className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  )
})
