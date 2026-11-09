import { forwardRef, useId, type SelectHTMLAttributes } from 'react'

type Props = SelectHTMLAttributes<HTMLSelectElement> & {
  label: string
  error?: string
  /** Help shown under the field and announced with it (aria-describedby). */
  hint?: string
  options: { value: string; label: string }[]
}

export const SelectField = forwardRef<HTMLSelectElement, Props>(function SelectField(
  { label, error, hint, options, ...select },
  ref,
) {
  const id = useId()
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy = [hint !== undefined ? hintId : undefined, error !== undefined ? errorId : undefined].filter(Boolean).join(' ')
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <select
        ref={ref}
        id={id}
        aria-invalid={error !== undefined}
        aria-describedby={describedBy === '' ? undefined : describedBy}
        {...select}
        className="h-10 px-2 rounded-ctl bg-surface border border-control text-sm focus:outline-2 focus:outline-brand"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
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
