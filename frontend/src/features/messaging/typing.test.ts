import { TypingSender } from './typing'

describe('TypingSender', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  function setup() {
    let now = 1_000_000
    const send = vi.fn()
    const sender = new TypingSender(send, () => now)
    return { send, sender, advance: (ms: number) => { now += ms; vi.advanceTimersByTime(ms) } }
  }

  it('sends "typing" once, not on every key press', () => {
    const { send, sender, advance } = setup()

    sender.keystroke()
    advance(300)
    sender.keystroke()
    advance(300)
    sender.keystroke()

    expect(send).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledWith(true)
  })

  it('repeats "typing" at most every 2 seconds while the user keeps typing', () => {
    const { send, sender, advance } = setup()

    sender.keystroke()
    advance(1500)
    sender.keystroke() // too soon
    advance(600)
    sender.keystroke() // 2.1 s after the first

    expect(send.mock.calls.filter(([typing]) => typing === true)).toHaveLength(2)
  })

  it('sends "stopped" once after 3 seconds without typing', () => {
    const { send, sender, advance } = setup()

    sender.keystroke()
    advance(3100)
    advance(3100)

    expect(send.mock.calls).toEqual([[true], [false]])
  })

  it('stop() (message sent or box emptied) sends "stopped" immediately, and only if typing', () => {
    const { send, sender } = setup()

    sender.stop() // nothing was typed
    expect(send).not.toHaveBeenCalled()

    sender.keystroke()
    sender.stop()
    sender.stop()
    expect(send.mock.calls).toEqual([[true], [false]])
  })
})
