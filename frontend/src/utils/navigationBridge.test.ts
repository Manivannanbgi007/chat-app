import { navigateTo, registerNavigator } from './navigationBridge'

describe('navigationBridge', () => {
  it('sends the path to the registered router function', () => {
    const navigate = vi.fn()
    const unregister = registerNavigator(navigate)

    navigateTo('/chats/c1')

    expect(navigate).toHaveBeenCalledWith('/chats/c1')
    unregister()
  })

  it('after unregistering, a newer registration is not removed by the old one', () => {
    const first = vi.fn()
    const second = vi.fn()
    const unregisterFirst = registerNavigator(first)
    const unregisterSecond = registerNavigator(second)

    unregisterFirst()
    navigateTo('/x')

    expect(second).toHaveBeenCalledWith('/x')
    expect(first).not.toHaveBeenCalled()
    unregisterSecond()
  })
})
