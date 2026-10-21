/** Only same-application paths are accepted as a return target (no open redirect, security.md §8). */
export function safeRedirect(target: string | null): string {
  return target !== null && target.startsWith('/') && !target.startsWith('//') && !target.startsWith('/\\')
    ? target
    : '/'
}
