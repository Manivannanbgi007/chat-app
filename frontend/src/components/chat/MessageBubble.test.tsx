import { render, screen } from '@testing-library/react'
import { ME, makeMessage } from '../../test/chatFixtures'
import MessageBubble from './MessageBubble'

function show(content: string) {
  render(<MessageBubble message={makeMessage({ senderId: ME, content })} mine onRetry={vi.fn()} />)
  return screen.getByText(content)
}

describe('MessageBubble: emoji', () => {
  it('a message of 1 to 3 emoji is shown big, without the coloured bubble', () => {
    const one = show('😀')
    expect(one.className).toContain('text-6xl')
    expect(screen.getByTestId('message').firstElementChild?.className).toContain('bg-transparent')
  })

  it('two emoji are a little smaller', () => {
    expect(show('😀😂').className).toContain('text-5xl')
  })

  it('normal text, and text with an emoji in it, stay in the normal bubble', () => {
    const text = show('Hello 😀')
    expect(text.className).not.toContain('text-6xl')
    expect(screen.getByTestId('message').firstElementChild?.className).toContain('from-brand-500')
  })
})
