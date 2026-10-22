import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Dialog } from './Dialog'

function Harness() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      {open && (
        <Dialog title="Block lot" onClose={() => setOpen(false)}>
          <label>
            Reason
            <input />
          </label>
          <button type="button">Confirm</button>
        </Dialog>
      )}
    </>
  )
}

describe('Dialog', () => {
  it('is a labelled modal that takes the focus and gives it back on Escape', async () => {
    render(<Harness />)
    const opener = screen.getByRole('button', { name: 'Open' })
    await userEvent.click(opener)

    const dialog = screen.getByRole('dialog', { name: 'Block lot' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByLabelText('Reason')).toHaveFocus()

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('keeps Tab inside the dialog', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))

    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByLabelText('Reason')).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus()
  })
})
