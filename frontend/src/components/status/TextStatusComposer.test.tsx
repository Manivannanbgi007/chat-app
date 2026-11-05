import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Mock } from 'vitest'
import { STATUS_COLORS } from '../../features/status/statusRules'
import { AppError } from '../../services/api/appError'
import { createStatus } from '../../services/api/statusApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { makeStatus } from '../../test/statusFixtures'
import type { Status } from '../../types/status'
import TextStatusComposer from './TextStatusComposer'

vi.mock('../../services/api/statusApi')

let onPosted: Mock<(status: Status) => void>
let onClose: Mock<() => void>

beforeEach(() => {
  vi.resetAllMocks()
  onPosted = vi.fn()
  onClose = vi.fn()
  renderWithProviders(<TextStatusComposer onPosted={onPosted} onClose={onClose} />)
})

describe('TextStatusComposer', () => {
  it('limits the text to 700 characters and counts them', async () => {
    const box = screen.getByRole('textbox', { name: 'Status text' })
    expect(box).toHaveAttribute('maxLength', '700')
    expect(box).toHaveFocus()
    await userEvent.type(box, 'Hello')
    expect(screen.getByText('5 / 700')).toBeInTheDocument()
  })

  it('switches through the 8 background colours', async () => {
    const editor = screen.getByTestId('text-status-composer')
    expect(editor).toHaveAttribute('data-color', STATUS_COLORS[0])
    for (let step = 1; step <= STATUS_COLORS.length; step++) {
      await userEvent.click(screen.getByRole('button', { name: 'Change background colour' }))
      expect(editor).toHaveAttribute('data-color', STATUS_COLORS[step % STATUS_COLORS.length])
    }
  })

  it('cannot post empty text', async () => {
    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
    await userEvent.type(screen.getByRole('textbox', { name: 'Status text' }), '   ')
    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })

  it('posts the text with the chosen colour', async () => {
    const saved = makeStatus()
    vi.mocked(createStatus).mockResolvedValue(saved)
    await userEvent.type(screen.getByRole('textbox', { name: 'Status text' }), 'Trip done!')
    await userEvent.click(screen.getByRole('button', { name: 'Change background colour' }))
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    expect(createStatus).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'TEXT', text: 'Trip done!', backgroundColor: STATUS_COLORS[1] }),
    )
    expect(onPosted).toHaveBeenCalledWith(saved)
  })

  it('shows the error and sends the SAME idempotency key again when Post is pressed after a network error', async () => {
    vi.mocked(createStatus).mockRejectedValueOnce(new AppError('NETWORK', 'offline')).mockResolvedValueOnce(makeStatus())
    await userEvent.type(screen.getByRole('textbox', { name: 'Status text' }), 'Hi')
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/No connection/)
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    const [first, second] = vi.mocked(createStatus).mock.calls.map((call) => call[0].idempotencyKey)
    expect(first).toBeTruthy()
    expect(second).toBe(first)
    expect(onPosted).toHaveBeenCalledTimes(1)
  })

  it('explains the limit of active statuses', async () => {
    vi.mocked(createStatus).mockRejectedValue(new AppError('UNKNOWN', 'x', 409, {}, 'STATUS_LIMIT_REACHED'))
    await userEvent.type(screen.getByRole('textbox', { name: 'Status text' }), 'One more')
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/30 active status updates/)
  })

  it('closes with the X and with Escape', async () => {
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
