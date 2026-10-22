import { forwardRef, useEffect, useId, useRef, useState, type FocusEvent, type InputHTMLAttributes } from 'react'
import { useTranslation } from 'react-i18next'

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { label: string; error?: string }

/**
 * Labelled password field with an eye button (frontend.md §5). Hidden by default; hidden again when the form
 * is submitted, when focus leaves the field and its button, and when the page is left (the state is local).
 * The value is only ever held by the input itself.
 */
export const PasswordInput = forwardRef<HTMLInputElement, Props>(function PasswordInput(
  { label, error, ...input },
  ref,
) {
  const { t } = useTranslation()
  const id = useId()
  const errorId = `${id}-error`
  const [visible, setVisible] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)

  const setRefs = (element: HTMLInputElement | null) => {
    inputRef.current = element
    if (typeof ref === 'function') {
      ref(element)
    } else if (ref) {
      ref.current = element
    }
  }

  // Successful or refused, a submit hides the password; the form's own handler still runs.
  useEffect(() => {
    const form = inputRef.current?.form
    if (!form) {
      return
    }
    const hide = () => setVisible(false)
    form.addEventListener('submit', hide)
    return () => form.removeEventListener('submit', hide)
  }, [])

  const hideWhenFocusLeaves = (event: FocusEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget)) {
      setVisible(false)
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <div className="relative" onBlur={hideWhenFocusLeaves}>
        <input
          ref={setRefs}
          id={id}
          aria-invalid={error !== undefined}
          aria-describedby={error !== undefined ? errorId : undefined}
          spellCheck={false}
          autoCapitalize="off"
          {...input}
          type={visible ? 'text' : 'password'}
          className="h-10 w-full ps-3 pe-11 rounded-ctl bg-surface border border-line text-sm focus:outline-2 focus:outline-brand aria-invalid:border-danger"
        />
        <button
          type="button"
          aria-pressed={visible}
          aria-label={visible ? t('passwordInput.hide') : t('passwordInput.show')}
          onClick={() => setVisible((v) => !v)}
          className="absolute inset-y-0 end-0 my-auto me-1 flex size-8 items-center justify-center rounded-ctl text-mute hover:text-ink focus-visible:outline-2 focus-visible:outline-brand"
        >
          <EyeIcon crossed={visible} />
        </button>
      </div>
      {error !== undefined && (
        <p id={errorId} className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  )
})

/** Open eye while hidden ("show"), crossed eye while shown ("hide"); colour from the text token. */
function EyeIcon({ crossed }: { crossed: boolean }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />
      {crossed && <path d="M3 3l18 18" />}
    </svg>
  )
}
