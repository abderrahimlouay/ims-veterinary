import { forwardRef, useEffect, useId, useRef, useState, type FocusEvent, type InputHTMLAttributes } from 'react'
import { useTranslation } from 'react-i18next'

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & {
  label: string
  error?: string
  /** Adds a copy button (one-time temporary password, ADR-045); the value is copied without being shown. */
  copyable?: boolean
}

/** How long the copy confirmation stays on screen. */
const COPY_MESSAGE_MS = 3000

/**
 * Labelled password field with an eye button (frontend.md §5). Hidden by default; hidden again when the form
 * is submitted, when focus leaves the field and its button, and when the page is left (the state is local).
 * The value is only ever held by the input itself (and the clipboard, when the user copies it).
 */
export const PasswordInput = forwardRef<HTMLInputElement, Props>(function PasswordInput(
  { label, error, copyable = false, ...input },
  ref,
) {
  const { t } = useTranslation()
  const id = useId()
  const errorId = `${id}-error`
  const [visible, setVisible] = useState(false)
  const [copyMessage, setCopyMessage] = useState<'copied' | 'copyFailed'>()
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

  useEffect(() => {
    if (copyMessage === undefined) {
      return
    }
    const timer = setTimeout(() => setCopyMessage(undefined), COPY_MESSAGE_MS)
    return () => clearTimeout(timer)
  }, [copyMessage])

  // The failure message never contains the value.
  const copy = () => {
    const value = inputRef.current?.value ?? ''
    navigator.clipboard.writeText(value).then(
      () => setCopyMessage('copied'),
      () => setCopyMessage('copyFailed'),
    )
  }

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
          className={`h-10 w-full ps-3 ${copyable ? 'pe-20' : 'pe-11'} rounded-ctl bg-surface border border-line text-sm focus:outline-2 focus:outline-brand aria-invalid:border-danger`}
        />
        {copyable && (
          <button
            type="button"
            aria-label={t('passwordInput.copy')}
            onClick={copy}
            className="absolute inset-y-0 end-9 my-auto me-1 flex size-8 items-center justify-center rounded-ctl text-mute hover:text-ink focus-visible:outline-2 focus-visible:outline-brand"
          >
            <CopyIcon />
          </button>
        )}
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
      {copyable && (
        <p role="status" className={`text-sm ${copyMessage === 'copyFailed' ? 'text-danger' : 'text-ink'}`}>
          {copyMessage && t(`passwordInput.${copyMessage}`)}
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

function CopyIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="9" width="12" height="12" rx="2" />
      <path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" />
    </svg>
  )
}
