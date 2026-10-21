import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError } from '../api/client'
import type { Problem } from '../api/problem'

/**
 * Puts server field errors (`errors[]`, and `context.field` of DUPLICATE_RESOURCE) on their form fields;
 * returns the problem when nothing could be placed, to be shown as a form-level message.
 */
export function placeServerErrors<T extends FieldValues>(
  error: unknown,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
): Problem | undefined {
  if (!(error instanceof ApiError)) {
    return undefined
  }
  const { problem } = error
  let placed = false
  for (const fieldError of problem.errors) {
    const field = fields.find((f) => f === fieldError.field)
    if (field !== undefined) {
      setError(field, { message: `fieldErrors.${fieldError.code}` })
      placed = true
    }
  }
  const duplicateField = fields.find((f) => f === problem.context.field)
  if (problem.code === 'DUPLICATE_RESOURCE' && duplicateField !== undefined) {
    setError(duplicateField, { message: 'fieldErrors.DUPLICATE' })
    placed = true
  }
  return placed ? undefined : problem
}
