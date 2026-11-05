import { makeCall } from '../../test/callFixtures'
import { ME, OTHER, THIRD, makeConversation, makeGroup, makeMediaMessage, makeMessage } from '../../test/chatFixtures'
import { useNotificationStore } from '../../store/notificationStore'
import * as navigationBridge from '../../utils/navigationBridge'
import {
  defaultDeps,
  desktopPermission,
  isDesktopOn,
  notificationBody,
  setDesktopChosen,
  showDesktopNotificationFor,
  showIncomingCallNotification,
  type DesktopDeps,
} from './localNotifications'

// Every test decides what the "browser" says: tab hidden or not, desktop notifications on or not.
function fakeDeps(overrides: Partial<{ hidden: boolean; on: boolean }> = {}) {
  const show = vi.fn<DesktopDeps['show']>()
  const deps: DesktopDeps = {
    isHidden: () => overrides.hidden ?? true,
    isOn: () => overrides.on ?? true,
    show,
  }
  return { deps, show }
}

describe('notificationBody', () => {
  it('the first 80 characters of a text message', () => {
    const text = 'x'.repeat(200)
    expect(notificationBody({ type: 'TEXT', content: text })).toBe('x'.repeat(80))
    expect(notificationBody({ type: 'TEXT', content: '  hi there  ' })).toBe('hi there')
  })

  it('a label for files', () => {
    expect(notificationBody({ type: 'IMAGE', content: 'a caption that is not shown' })).toBe('Photo')
    expect(notificationBody({ type: 'VOICE', content: '' })).toBe('Voice message')
    expect(notificationBody({ type: 'DOCUMENT', content: '' })).toBe('Document')
    expect(notificationBody({ type: 'VIDEO', content: '' })).toBe('Video')
    expect(notificationBody({ type: 'AUDIO', content: '' })).toBe('Audio')
  })

  it('"New message" for anything else', () => {
    expect(notificationBody({ type: 'TEXT', content: '   ' })).toBe('New message')
    expect(notificationBody({ type: 'LOCATION' as never, content: '' })).toBe('New message')
  })
})

describe('showDesktopNotificationFor', () => {
  const conversation = makeConversation() // private chat with Ravi (OTHER)

  it('shows a pop-up titled with the sender, tagged with the chat, when the tab is hidden', () => {
    const { deps, show } = fakeDeps()
    const message = makeMessage({ senderId: OTHER, content: 'lunch?', conversationId: 'c1' })

    expect(showDesktopNotificationFor(message, ME, conversation, deps)).toBe(true)

    expect(show).toHaveBeenCalledWith('Ravi Kumar', { body: 'lunch?', tag: 'c1' }, expect.any(Function))
  })

  it('shows the label for a photo', () => {
    const { deps, show } = fakeDeps()
    showDesktopNotificationFor(makeMediaMessage({ kind: 'IMAGE' }, { senderId: OTHER }), ME, conversation, deps)

    expect(show.mock.calls[0][1].body).toBe('Photo')
  })

  it('in a group the title is the name of the person who wrote', () => {
    const { deps, show } = fakeDeps()
    showDesktopNotificationFor(makeMessage({ senderId: THIRD, conversationId: 'g1' }), ME, makeGroup(), deps)

    expect(show.mock.calls[0][0]).toBe('Meena Iyer')
  })

  it('a chat we do not know yet still shows "New message" as the title', () => {
    const { deps, show } = fakeDeps()
    showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, undefined, deps)

    expect(show.mock.calls[0][0]).toBe('New message')
  })

  it('never for my own messages', () => {
    const { deps, show } = fakeDeps()
    expect(showDesktopNotificationFor(makeMessage({ senderId: ME }), ME, conversation, deps)).toBe(false)
    expect(show).not.toHaveBeenCalled()
  })

  it('never for SYSTEM notes about group changes', () => {
    const { deps, show } = fakeDeps()
    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER, type: 'SYSTEM' }), ME, conversation, deps)).toBe(false)
    expect(show).not.toHaveBeenCalled()
  })

  it('not while the tab is visible', () => {
    const { deps, show } = fakeDeps({ hidden: false })
    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(false)
    expect(show).not.toHaveBeenCalled()
  })

  it('not when the user did not turn desktop notifications on (or the browser did not allow them)', () => {
    const { deps, show } = fakeDeps({ on: false })
    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(false)
    expect(show).not.toHaveBeenCalled()
  })

  describe('follows the switches of Settings > Notifications', () => {
    const group = makeGroup()
    afterEach(() => useNotificationStore.getState().setPreferences(null))

    it('unknown switches (not loaded yet) do not hide anything', () => {
      const { deps, show } = fakeDeps()
      useNotificationStore.getState().setPreferences(null)
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(true)
      expect(show).toHaveBeenCalledTimes(1)
    })

    it('"Push notifications" off: no pop-up for anything', () => {
      const { deps, show } = fakeDeps()
      useNotificationStore.getState().setPreferences({ pushEnabled: false, messagesEnabled: true, groupsEnabled: true, callsEnabled: true })
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(false)
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, group, deps)).toBe(false)
      expect(show).not.toHaveBeenCalled()
    })

    it('"New messages in private chats" off: no pop-up for a private chat, but still for a group', () => {
      const { deps, show } = fakeDeps()
      useNotificationStore.getState().setPreferences({ pushEnabled: true, messagesEnabled: false, groupsEnabled: true, callsEnabled: true })
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(false)
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, group, deps)).toBe(true)
      expect(show).toHaveBeenCalledTimes(1)
    })

    it('"Groups" off: no pop-up for a group, but still for a private chat', () => {
      const { deps, show } = fakeDeps()
      useNotificationStore.getState().setPreferences({ pushEnabled: true, messagesEnabled: true, groupsEnabled: false, callsEnabled: true })
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, group, deps)).toBe(false)
      expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(true)
      expect(show).toHaveBeenCalledTimes(1)
    })
  })

  it('not for a muted chat, but again when the mute is over', () => {
    const { deps, show } = fakeDeps()
    const future = new Date(Date.now() + 3600_000).toISOString()
    const past = new Date(Date.now() - 3600_000).toISOString()
    const muted = makeConversation({ preferences: { mutedUntil: future, archived: false, pinned: false } })
    const unmuted = makeConversation({ preferences: { mutedUntil: past, archived: false, pinned: false } })

    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, muted, deps)).toBe(false)
    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, unmuted, deps)).toBe(true)
    expect(show).toHaveBeenCalledTimes(1)
  })

  it('never breaks message handling when the browser refuses', () => {
    const show = vi.fn(() => {
      throw new Error('Illegal constructor')
    })
    const deps: DesktopDeps = { isHidden: () => true, isOn: () => true, show }

    expect(showDesktopNotificationFor(makeMessage({ senderId: OTHER }), ME, conversation, deps)).toBe(false)
  })

  it('clicking the pop-up focuses the window and opens the chat', () => {
    const { deps, show } = fakeDeps()
    const navigate = vi.spyOn(navigationBridge, 'navigateTo').mockImplementation(() => undefined)
    const focus = vi.spyOn(window, 'focus').mockImplementation(() => undefined)

    showDesktopNotificationFor(makeMessage({ senderId: OTHER, conversationId: 'c42' }), ME, conversation, deps)
    const onClick = show.mock.calls[0][2]
    onClick()

    expect(focus).toHaveBeenCalled()
    expect(navigate).toHaveBeenCalledWith('/chats/c42')
    navigate.mockRestore()
    focus.mockRestore()
  })
})

describe('the real browser connection (Notification class)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('is off without a Notification class (unsupported browser)', () => {
    setDesktopChosen(true)
    expect(desktopPermission()).toBe('unsupported')
    expect(isDesktopOn()).toBe(false)
  })

  it('is on only when chosen AND granted', () => {
    vi.stubGlobal('Notification', { permission: 'granted' })
    expect(isDesktopOn()).toBe(false) // nothing chosen yet
    setDesktopChosen(true)
    expect(isDesktopOn()).toBe(true)

    vi.stubGlobal('Notification', { permission: 'denied' })
    expect(isDesktopOn()).toBe(false)
  })

  it('creates a browser Notification and closes it after a click', () => {
    const created: { title: string; options: unknown; closed: boolean; onclick: (() => void) | null }[] = []
    class FakeNotification {
      static permission = 'granted'
      onclick: (() => void) | null = null
      closed = false
      title: string
      options: unknown
      constructor(title: string, options: unknown) {
        this.title = title
        this.options = options
        created.push(this)
      }
      close() {
        this.closed = true
      }
    }
    vi.stubGlobal('Notification', FakeNotification)
    const clicked = vi.fn()

    defaultDeps.show('Ravi Kumar', { body: 'hi', tag: 'c1' }, clicked)
    created[0].onclick?.()

    expect(created[0].title).toBe('Ravi Kumar')
    expect(clicked).toHaveBeenCalled()
    expect(created[0].closed).toBe(true)
  })
})

describe('showIncomingCallNotification', () => {
  it('pops up "Incoming video call" with the name of the caller while the tab is hidden', () => {
    const { deps, show } = fakeDeps()
    expect(showIncomingCallNotification(makeCall({ direction: 'INCOMING' }), deps)).toBe(true)
    expect(show).toHaveBeenCalledWith('Ravi Kumar', { body: 'Incoming video call', tag: 'call-call-1' }, expect.any(Function))
  })

  it('stays quiet when the tab is visible or desktop notifications are off', () => {
    const visible = fakeDeps({ hidden: false })
    const off = fakeDeps({ on: false })
    expect(showIncomingCallNotification(makeCall({ type: 'AUDIO' }), visible.deps)).toBe(false)
    expect(showIncomingCallNotification(makeCall({ type: 'AUDIO' }), off.deps)).toBe(false)
    expect(visible.show).not.toHaveBeenCalled()
    expect(off.show).not.toHaveBeenCalled()
  })
})
