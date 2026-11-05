// "Desktop notifications" of the browser (the small pop-up of the operating system) for messages that arrive while
// the tab is hidden. They are created HERE, on this computer: nothing is sent through any server, and they only
// work while the app is open in some tab. (Real push to a closed browser needs FCM, which is not part of this app yet.)
import { callKindLabel } from '../calls/callText'
import { KIND_LABEL } from '../media/mediaRules'
import { currentName, isMuted } from '../messaging/conversationInfo'
import type { Call } from '../../types/call'
import type { Conversation, Message } from '../../types/chat'
import { useNotificationStore } from '../../store/notificationStore'
import { navigateTo } from '../../utils/navigationBridge'
import { readStorage, writeStorage } from '../../utils/storage'

const STORAGE_KEY = 'chat-app-desktop-notifications'
const MAX_BODY_CHARS = 80

// ---- the user's choice (this device only) ----

// Is the browser able to show them at all?
export function isDesktopSupported(): boolean {
  return typeof Notification !== 'undefined'
}

// 'granted' | 'denied' | 'default' | 'unsupported'
export function desktopPermission(): NotificationPermission | 'unsupported' {
  return isDesktopSupported() ? Notification.permission : 'unsupported'
}

// What the user chose in Settings (stored in this browser).
export function isDesktopChosen(): boolean {
  return readStorage(STORAGE_KEY) === 'true'
}

export function setDesktopChosen(value: boolean): void {
  writeStorage(STORAGE_KEY, String(value))
}

// Chosen AND allowed by the browser.
export function isDesktopOn(): boolean {
  return isDesktopChosen() && desktopPermission() === 'granted'
}

// ---- showing one ----

// The text of the pop-up: the first 80 characters of a text message, "Photo" / "Voice message" ... for files.
// (A message is the user's private data: we show only as much as the user's own screen needs.)
export function notificationBody(message: Pick<Message, 'type' | 'content'>): string {
  if (message.type === 'TEXT') {
    const text = message.content.trim()
    return text ? text.slice(0, MAX_BODY_CHARS) : 'New message'
  }
  return KIND_LABEL[message.type as keyof typeof KIND_LABEL] ?? 'New message' // a type this app does not know yet
}

// The switches of Settings > Notifications also decide about the pop-ups: "Push notifications" is the master switch,
// "New messages in private chats" / "Groups" decide per kind of chat. Unknown (not loaded yet) = allowed.
export function allowedBySwitches(conversation: Conversation | undefined): boolean {
  const preferences = useNotificationStore.getState().preferences
  if (!preferences) return true
  if (!preferences.pushEnabled) return false
  return conversation?.type === 'GROUP' ? preferences.groupsEnabled : preferences.messagesEnabled
}

// What the browser needs to show one (tests replace it).
export interface DesktopDeps {
  isHidden: () => boolean
  isOn: () => boolean
  show: (title: string, options: { body: string; tag: string }, onClick: () => void) => void
}

export const defaultDeps: DesktopDeps = {
  isHidden: () => document.visibilityState !== 'visible',
  isOn: isDesktopOn,
  show: (title, options, onClick) => {
    const popup = new Notification(title, options)
    popup.onclick = () => {
      onClick()
      popup.close()
    }
  },
}

// Shows the pop-up when ALL of these are true: it is a message from somebody else, a normal one (not a SYSTEM note
// about a group change), the tab is hidden, the user turned desktop notifications on and the browser allowed it,
// and the chat is not muted. Returns true when a pop-up was shown.
export function showDesktopNotificationFor(
  message: Message,
  meId: string,
  conversation: Conversation | undefined,
  deps: DesktopDeps = defaultDeps,
): boolean {
  if (message.senderId === meId || message.type === 'SYSTEM') return false
  if (conversation && isMuted(conversation)) return false
  if (!allowedBySwitches(conversation)) return false
  try {
    if (!deps.isHidden() || !deps.isOn()) return false

    const sender = currentName(conversation, message.senderId) ?? conversation?.otherUser?.displayName ?? 'New message'
    deps.show(sender, { body: notificationBody(message), tag: message.conversationId }, () => {
      window.focus()
      navigateTo(`/chats/${message.conversationId}`)
    })
    return true
  } catch {
    return false // the browser does not support it, or refused: never break message handling because of it
  }
}

// Someone is calling me while the tab is hidden: a pop-up "Asha Rao - Incoming video call" (same switch and browser
// permission as for messages). Clicking it brings the tab to the front, where the call screen is already ringing.
// Returns true when a pop-up was shown.
export function showIncomingCallNotification(call: Call, deps: DesktopDeps = defaultDeps): boolean {
  try {
    if (!deps.isHidden() || !deps.isOn()) return false
    deps.show(call.otherUser.displayName, { body: `Incoming ${callKindLabel(call.type)}`, tag: `call-${call.id}` }, () => {
      window.focus()
    })
    return true
  } catch {
    return false // never break the call because of a pop-up
  }
}
