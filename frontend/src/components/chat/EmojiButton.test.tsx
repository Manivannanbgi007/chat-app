import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import EmojiButton from './EmojiButton'

beforeEach(() => localStorage.clear())

describe('EmojiButton', () => {
  it('opens a panel, picks an emoji, and shows it first under "Recently used" next time', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    const { unmount } = render(<EmojiButton onPick={onPick} />)

    expect(screen.queryByRole('dialog', { name: 'Emoji' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add emoji' }))
    await user.click(screen.getByRole('tab', { name: 'Food & drink' }))
    await user.click(screen.getByRole('button', { name: /^pizza/ }))
    expect(onPick).toHaveBeenCalledWith('🍕')
    unmount()

    // a new panel starts on the "recently used" tab
    render(<EmojiButton onPick={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Add emoji' }))
    expect(screen.getByRole('tab', { name: 'Recently used', selected: true })).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Recently used' })).getByRole('button')).toHaveTextContent('🍕')
  })

  it('has a tab for every group and shows that group', async () => {
    const user = userEvent.setup()
    render(<EmojiButton onPick={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Add emoji' }))

    await user.click(screen.getByRole('tab', { name: 'Animals & nature' }))
    expect(screen.getByRole('list', { name: 'Animals & nature' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^dog face/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^pizza/ })).not.toBeInTheDocument()
  })

  it('the search box filters, says so when nothing matches, and Enter picks the first result', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(<EmojiButton onPick={onPick} />)
    await user.click(screen.getByRole('button', { name: 'Add emoji' }))

    await user.type(screen.getByRole('searchbox', { name: 'Search emoji' }), 'zzzzzz')
    expect(screen.getByRole('status')).toHaveTextContent('No emoji found')

    await user.clear(screen.getByRole('searchbox', { name: 'Search emoji' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search emoji' }), 'pizza{Enter}')
    expect(onPick).toHaveBeenCalledWith('🍕')
  })

  it('closes with Escape and with a click outside', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <p>outside</p>
        <EmojiButton onPick={vi.fn()} />
      </div>,
    )
    await user.click(screen.getByRole('button', { name: 'Add emoji' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Emoji' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Add emoji' }))
    await user.click(screen.getByText('outside'))
    expect(screen.queryByRole('dialog', { name: 'Emoji' })).not.toBeInTheDocument()
  })

  it('is disabled while disabled', () => {
    render(<EmojiButton onPick={vi.fn()} disabled />)
    expect(screen.getByRole('button', { name: 'Add emoji' })).toBeDisabled()
  })
})
