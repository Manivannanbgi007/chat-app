// Pure functions (no React, no network) that keep a conversation's message list correct.
// They are the heart of the chat state.

const STATUS_RANK = { SENT: 1, DELIVERED: 2, READ: 3 }

// A status only moves forward: SENT -> DELIVERED -> READ. Late or repeated updates never go back.
export function maxStatus(current, incoming) {
  if (!incoming) return current
  if (!current) return incoming
  return STATUS_RANK[incoming] > STATUS_RANK[current] ? incoming : current
}

// Same idea for what *my* devices acknowledged on a message sent to me ('DELIVERED' | 'READ' | null).
export function maxReceipt(current, incoming) {
  return maxStatus(current, incoming)
}

// "2026-11-04T10:00:00.123456Z" -> microseconds as a number. The server sends microsecond precision,
// but a plain Date only knows milliseconds, so the digits after the dot are added by hand.
export function toMicros(iso) {
  const match = /^(.*?)(?:\.(\d+))?(Z|[+-]\d\d:?\d\d)?$/.exec(iso)
  if (!match) return Date.parse(iso) * 1000
  const [, base, fraction = '', zone = 'Z'] = match
  const millis = Date.parse(`${base}${zone}`)
  const micros = Number(fraction.padEnd(6, '0').slice(0, 6))
  return millis * 1000 + micros
}

// Oldest first. Same time -> the id decides, exactly like the server's (createdAt, id) order.
// Messages that are still being sent always come last (they have no server time yet).
export function compareMessages(a, b) {
  const aPending = a.localStatus !== undefined
  const bPending = b.localStatus !== undefined
  if (aPending !== bPending) return aPending ? 1 : -1
  const byTime = toMicros(a.createdAt) - toMicros(b.createdAt)
  if (byTime !== 0) return byTime
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

// Takes a message that came from the server and combines it with what we already know.
// - the status never goes backwards
// - the fields that only the server knows win (id, createdAt)
function mergeServerMessage(existing, incoming) {
  if (!existing) return { ...incoming }
  return {
    ...incoming,
    status: maxStatus(existing.status, incoming.status),
    myReceipt: maxReceipt(existing.myReceipt, incoming.myReceipt),
  }
}

// Text messages: no attachment on either side. Media messages: the same uploaded file. A message whose file is
// still uploading (no media id yet) is never matched: it cannot have been saved by the server yet.
function sameAttachment(pending, saved) {
  if (!pending.attachment && !saved.attachment) return true
  return !!pending.attachment?.mediaId && pending.attachment.mediaId === saved.attachment?.mediaId
}

// Adds server messages to a list and returns the new sorted list WITHOUT duplicates.
// Duplicates are normal: a message can arrive live, again in the history page and again in a sync.
//
// A message I sent shows up twice for a short time: my pending copy ("local-...", no server id yet)
// and the server copy that arrives through the socket before the POST answer. We recognise the pending
// copy by (same conversation, I am the sender, same text) and drop it.
export function mergeMessages(existing, incoming, currentUserId) {
  const byId = new Map()
  for (const message of existing) {
    byId.set(message.id, message)
  }

  for (const message of incoming) {
    if (message.senderId === currentUserId && !byId.has(message.id)) {
      const pending = [...byId.values()].find(
        (candidate) =>
          candidate.localStatus === 'SENDING' &&
          candidate.conversationId === message.conversationId &&
          candidate.type === message.type &&
          candidate.content === message.content &&
          sameAttachment(candidate, message),
      )
      if (pending) {
        byId.delete(pending.id)
      }
    }
    byId.set(message.id, mergeServerMessage(byId.get(message.id), message))
  }

  return [...byId.values()].sort(compareMessages)
}

// The POST /api/messages answer (or a MESSAGE_ACK) for a message I sent: swap my pending copy for the server one.
// If the server copy is already in the list (it arrived live first) the pending copy just disappears.
export function replacePending(existing, idempotencyKey, serverMessage, currentUserId) {
  const withoutPending = existing.filter((message) => message.idempotencyKey !== idempotencyKey)
  return mergeMessages(withoutPending, [serverMessage], currentUserId)
}

// Updates the status of one of my messages from a receipt event (never backwards).
export function applyReceipt(existing, messageId, status) {
  let changed = false
  const next = existing.map((message) => {
    if (message.id !== messageId) return message
    const merged = maxStatus(message.status, status)
    if (merged === message.status) return message
    changed = true
    return { ...message, status: merged }
  })
  return changed ? next : existing
}

// Marks messages sent to me as read by me (used right after the read request).
export function markIncomingRead(existing, currentUserId) {
  let changed = false
  const next = existing.map((message) => {
    if (message.senderId === currentUserId || message.localStatus || message.myReceipt === 'READ' || message.type === 'SYSTEM') {
      return message // SYSTEM messages are never read or acknowledged
    }
    changed = true
    return { ...message, myReceipt: 'READ' }
  })
  return changed ? next : existing
}

// What the tick next to a message should show: 'SENDING' | 'FAILED' | 'SENT' | 'DELIVERED' | 'READ' | null.
export function displayStatus(message) {
  if (message.localStatus) return message.localStatus
  return message.status
}
