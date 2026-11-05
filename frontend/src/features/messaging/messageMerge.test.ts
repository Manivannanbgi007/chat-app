import { ME, OTHER, makeMessage } from '../../test/chatFixtures'
import type { ChatMessage } from '../../types/chat'
import {
  applyReceipt,
  compareMessages,
  displayStatus,
  markIncomingRead,
  maxStatus,
  mergeMessages,
  replacePending,
  toMicros,
} from './messageMerge'

function pending(content: string, key: string, createdAt = '2026-11-04T10:00:09.000Z'): ChatMessage {
  return {
    id: `local-${key}`,
    conversationId: 'c1',
    senderId: ME,
    type: 'TEXT',
    content,
    createdAt,
    status: null,
    myReceipt: null,
    localStatus: 'SENDING',
    idempotencyKey: key,
  }
}

describe('status only moves forward', () => {
  it('keeps the higher status', () => {
    expect(maxStatus('SENT', 'DELIVERED')).toBe('DELIVERED')
    expect(maxStatus('DELIVERED', 'READ')).toBe('READ')
    expect(maxStatus('READ', 'DELIVERED')).toBe('READ') // a late "delivered" never downgrades
    expect(maxStatus('DELIVERED', 'SENT')).toBe('DELIVERED')
    expect(maxStatus(null, 'SENT')).toBe('SENT')
    expect(maxStatus('READ', null)).toBe('READ')
  })

  it('applyReceipt ignores downgrades and unknown messages', () => {
    const list = [makeMessage({ id: 'a', senderId: ME, status: 'READ' }), makeMessage({ id: 'b', senderId: ME, status: 'SENT' })]

    expect(applyReceipt(list, 'a', 'DELIVERED')).toBe(list) // nothing changed -> the same array
    expect(applyReceipt(list, 'zzz', 'READ')).toBe(list)
    const next = applyReceipt(list, 'b', 'DELIVERED')
    expect(next[1].status).toBe('DELIVERED')
    expect(next[0]).toBe(list[0])
  })
})

describe('toMicros / ordering', () => {
  it('reads microsecond precision that Date cannot', () => {
    const a = toMicros('2026-11-04T10:00:00.123456Z')
    const b = toMicros('2026-11-04T10:00:00.123457Z')
    expect(b - a).toBe(1)
    expect(toMicros('2026-11-04T10:00:00Z')).toBe(toMicros('2026-11-04T10:00:00.000000Z'))
    expect(toMicros('2026-11-04T10:00:00.5Z')).toBe(toMicros('2026-11-04T10:00:00.500000Z'))
  })

  it('orders by time, then by id, and puts pending messages last', () => {
    const same1 = makeMessage({ id: 'a', createdAt: '2026-11-04T10:00:00.000001Z' })
    const same2 = makeMessage({ id: 'b', createdAt: '2026-11-04T10:00:00.000001Z' })
    const later = makeMessage({ id: '0', createdAt: '2026-11-04T10:00:01.000000Z' })
    const sending = pending('x', 'k1', '2020-01-01T00:00:00.000Z') // oldest clock, still last

    const sorted = [sending, later, same2, same1].sort(compareMessages)

    expect(sorted.map((m) => m.id)).toEqual(['a', 'b', '0', 'local-k1'])
  })
})

describe('mergeMessages', () => {
  it('removes duplicates by id (live event + history page + sync)', () => {
    const m1 = makeMessage({ id: 'm1' })
    const m2 = makeMessage({ id: 'm2' })

    const once = mergeMessages([], [m1, m2], ME)
    const again = mergeMessages(once, [m2, m1, m2], ME)

    expect(again.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('keeps messages sorted when older history arrives later', () => {
    const newest = makeMessage({ id: 'new', createdAt: '2026-11-04T12:00:00.000000Z' })
    const oldest = makeMessage({ id: 'old', createdAt: '2026-11-04T09:00:00.000000Z' })

    const merged = mergeMessages(mergeMessages([], [newest], ME), [oldest], ME)

    expect(merged.map((m) => m.id)).toEqual(['old', 'new'])
  })

  it('never lowers an already known status when an older copy arrives', () => {
    const delivered = makeMessage({ id: 'm1', senderId: ME, status: 'DELIVERED' })
    const staleCopy = makeMessage({ id: 'm1', senderId: ME, status: 'SENT' }) // e.g. from a slow history page

    const merged = mergeMessages([delivered], [staleCopy], ME)

    expect(merged[0].status).toBe('DELIVERED')
  })

  it('drops my pending copy when the same message arrives from the server first', () => {
    const waiting = pending('hi there', 'k1')
    const fromServer = makeMessage({ id: 'srv-1', senderId: ME, content: 'hi there' })

    const merged = mergeMessages([waiting], [fromServer], ME)

    expect(merged).toHaveLength(1)
    expect(merged[0].id).toBe('srv-1')
    expect(merged[0].localStatus).toBeUndefined()
  })

  it('does not drop a pending message with different text, or another user\'s identical text', () => {
    const waiting = pending('hi there', 'k1')

    const differentText = mergeMessages([waiting], [makeMessage({ id: 's1', senderId: ME, content: 'other' })], ME)
    const fromOtherUser = mergeMessages([waiting], [makeMessage({ id: 's2', senderId: OTHER, content: 'hi there' })], ME)

    expect(differentText).toHaveLength(2)
    expect(fromOtherUser).toHaveLength(2)
  })

  it('two identical pending texts are matched one by one', () => {
    const list = [pending('ok', 'k1'), pending('ok', 'k2', '2026-11-04T10:00:10.000Z')]

    const afterFirst = mergeMessages(list, [makeMessage({ id: 's1', senderId: ME, content: 'ok' })], ME)
    expect(afterFirst.filter((m) => m.localStatus)).toHaveLength(1)

    const afterSecond = mergeMessages(afterFirst, [makeMessage({ id: 's2', senderId: ME, content: 'ok' })], ME)
    expect(afterSecond.filter((m) => m.localStatus)).toHaveLength(0)
    expect(afterSecond).toHaveLength(2)
  })
})

describe('replacePending', () => {
  it('swaps my pending copy for the server message', () => {
    const waiting = pending('hello', 'k1')
    const saved = makeMessage({ id: 'srv', senderId: ME, content: 'hello' })

    const next = replacePending([waiting], 'k1', saved, ME)

    expect(next.map((m) => m.id)).toEqual(['srv'])
  })

  it('leaves exactly one message when the server copy already arrived live', () => {
    const saved = makeMessage({ id: 'srv', senderId: ME, content: 'hello', status: 'DELIVERED' })
    const list = mergeMessages([pending('hello', 'k1')], [saved], ME) // live event came first

    const next = replacePending(list, 'k1', { ...saved, status: 'SENT' }, ME)

    expect(next).toHaveLength(1)
    expect(next[0].status).toBe('DELIVERED') // and it did not go back to SENT
  })
})

describe('other helpers', () => {
  it('markIncomingRead only touches messages sent to me', () => {
    const incoming = makeMessage({ id: 'in', senderId: OTHER, myReceipt: null })
    const mine = makeMessage({ id: 'mine', senderId: ME })

    const [a, b] = markIncomingRead([incoming, mine], ME)

    expect(a.myReceipt).toBe('READ')
    expect(b).toBe(mine)
  })

  it('displayStatus prefers the local sending state', () => {
    expect(displayStatus(pending('x', 'k'))).toBe('SENDING')
    expect(displayStatus({ ...pending('x', 'k'), localStatus: 'FAILED' })).toBe('FAILED')
    expect(displayStatus(makeMessage({ senderId: ME, status: 'READ' }))).toBe('READ')
    expect(displayStatus(makeMessage({ senderId: OTHER }))).toBeNull()
  })
})

describe('pending media messages', () => {
  const file = (mediaId: string) => ({
    mediaId, kind: 'IMAGE' as const, fileName: 'holiday.png', mimeType: 'image/png', sizeBytes: 10,
    width: 8, height: 8, durationSeconds: null, hasThumbnail: true,
  })
  const pendingPhoto = (mediaId: string, caption = ''): ChatMessage => ({
    ...pending(caption, 'p1'),
    type: 'IMAGE',
    attachment: file(mediaId),
  })
  const savedPhoto = (mediaId: string, caption = '') =>
    makeMessage({ id: 'srv-photo', senderId: ME, type: 'IMAGE', content: caption, attachment: file(mediaId) })

  it('replaces my pending photo when the server copy arrives live first (same uploaded file)', () => {
    const result = mergeMessages([pendingPhoto('media-1', 'Look')], [savedPhoto('media-1', 'Look')], ME)

    expect(result.map((m) => m.id)).toEqual(['srv-photo'])
  })

  it('does NOT replace a pending photo whose file is still uploading (it cannot be saved yet)', () => {
    const result = mergeMessages([pendingPhoto('', 'Look')], [savedPhoto('media-1', 'Look')], ME)

    expect(result).toHaveLength(2)
  })

  it('does not mix up two different files with the same caption', () => {
    const result = mergeMessages([pendingPhoto('media-2', 'Look')], [savedPhoto('media-1', 'Look')], ME)

    expect(result).toHaveLength(2)
  })

  it('does not mistake a text message for a photo with the same text', () => {
    const result = mergeMessages([pending('Look', 'p2')], [savedPhoto('media-1', 'Look')], ME)

    expect(result).toHaveLength(2)
  })
})
