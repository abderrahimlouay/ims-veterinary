import type { Problem } from '../../api/problem'
import type { AllocationWrite } from './accountApi'

/** An open document an amount can be allocated to: an invoice of a customer or of a supplier. */
export interface Allocatable {
  id: number
  number: string
  open: string
}

/** The field errors of a refused write, by field name (api.md §3); empty when none is a field. */
export function fieldErrors(problem: Problem, t: (key: string) => string): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const e of problem.errors) {
    errors[e.field] = t(`fieldErrors.${e.code}`)
  }
  return errors
}

/** The filled amounts, in the order of the documents, so a field error's index matches its row. */
export function allocationsOf(invoices: Allocatable[], amounts: Record<number, string>): AllocationWrite[] {
  return invoices.filter((i) => (amounts[i.id] ?? '').trim() !== '').map((i) => ({ invoiceId: i.id, amount: amounts[i.id].trim() }))
}
