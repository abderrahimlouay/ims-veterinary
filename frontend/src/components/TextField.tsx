import { forwardRef, useId, type InputHTMLAttributes } from 'react'

type Props = InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string }

/** Labelled input; the error is announced with the field (aria-describedby). */
export const TextField = forwardRef<HTMLInputElement, Props>(function TextField({ label, error, ...input }, ref) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error !== undefined}
        aria-describedby={error !== undefined ? errorId : undefined}
        {...input}
        className="h-10 px-3 rounded-ctl bg-surface border border-line text-sm focus:outline-2 focus:outline-brand aria-invalid:border-danger"
      />
      {error !== undefined && (
        <p id={errorId} className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  )
})
