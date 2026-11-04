import { ApiError } from '../../api/client'
import { networkProblem, normaliseProblem } from '../../api/problem'

/**
 * Printing (frontend.md §13, ADR-139): the server renders every document; the browser shows or prints it. A PDF
 * opens in a new tab; a ticket prints at once from a hidden frame, nothing printing automatically.
 */
export function salePdfUrl(id: number, format: 'a4' | 'ticket' = 'a4'): string {
  return `/api/sales/${id}/pdf?format=${format}`
}

/**
 * Fetches the PDF and opens the print dialog on it in a hidden frame. The frame shows a `blob:` copy, so the API's
 * `X-Frame-Options: DENY` and `frame-ancestors 'none'` (security.md) stay as they are. Rejects with an
 * {@link ApiError} when the server refuses (a draft, a missing permission).
 */
export async function printInFrame(url: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(new URL(url, window.location.origin), { headers: { Accept: 'application/pdf, application/problem+json' } })
  } catch {
    throw new ApiError(networkProblem())
  }
  if (!response.ok) {
    const text = await response.text()
    let body: unknown = text
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
    throw new ApiError(normaliseProblem(response.status, response.headers, body))
  }
  const blob = URL.createObjectURL(await response.blob())
  const frame = document.createElement('iframe')
  frame.style.position = 'fixed'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  frame.setAttribute('aria-hidden', 'true')
  frame.setAttribute('data-testid', 'print-frame')
  frame.onload = () => {
    frame.contentWindow?.focus()
    frame.contentWindow?.print()
    window.setTimeout(() => {
      frame.remove()
      URL.revokeObjectURL(blob)
    }, 60_000)
  }
  frame.src = blob
  document.body.appendChild(frame)
}
