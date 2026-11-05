import { ME, OTHER, THIRD, makeConversation, makeGroup, makeMessage, makeSystemMessage, profile } from '../../test/chatFixtures'
import {
  canEditGroupInfo,
  conversationTitle,
  isAdmin,
  isIncoming,
  isMuted,
  memberNamesLine,
  sendBlockReason,
  senderColourClass,
  typingText,
} from './conversationInfo'

describe('conversationTitle', () => {
  it('is the group name, or the other person, and never crashes without one', () => {
    expect(conversationTitle(makeGroup())).toBe('Trip to Goa')
    expect(conversationTitle(makeConversation())).toBe('Ravi Kumar')
    expect(conversationTitle(makeConversation({ otherUser: null }))).toBe('Unknown user')
  })
})

describe('who may do what', () => {
  it('admins and members of an active group', () => {
    expect(isAdmin(makeGroup({}, { role: 'ADMIN' }))).toBe(true)
    expect(isAdmin(makeGroup({}, { role: 'MEMBER' }))).toBe(false)
    expect(isAdmin(makeConversation())).toBe(false)
  })

  it('sending: blocked for former members, and for non-admins when only admins may send', () => {
    expect(sendBlockReason(makeConversation())).toBeNull()
    expect(sendBlockReason(makeGroup())).toBeNull()
    expect(sendBlockReason(makeGroup({}, { active: false }))).toBe('NOT_A_MEMBER')
    expect(sendBlockReason(makeGroup({}, { role: 'MEMBER', onlyAdminsCanSend: true }))).toBe('ONLY_ADMINS')
    expect(sendBlockReason(makeGroup({}, { role: 'ADMIN', onlyAdminsCanSend: true }))).toBeNull()
  })

  it('editing the group info: admins always, members only when the group allows it', () => {
    expect(canEditGroupInfo(makeGroup({}, { role: 'ADMIN', onlyAdminsCanEdit: true }))).toBe(true)
    expect(canEditGroupInfo(makeGroup({}, { role: 'MEMBER', onlyAdminsCanEdit: false }))).toBe(true)
    expect(canEditGroupInfo(makeGroup({}, { role: 'MEMBER', onlyAdminsCanEdit: true }))).toBe(false)
    expect(canEditGroupInfo(makeGroup({}, { active: false }))).toBe(false)
    expect(canEditGroupInfo(makeConversation())).toBe(false)
  })
})

describe('isMuted', () => {
  const now = Date.parse('2026-11-04T10:00:00Z')
  it('is muted while mutedUntil is in the future', () => {
    const until = (mutedUntil: string | null) => makeConversation({ preferences: { mutedUntil, archived: false, pinned: false } })
    expect(isMuted(until(null), now)).toBe(false)
    expect(isMuted(until('2026-11-04T09:00:00Z'), now)).toBe(false)
    expect(isMuted(until('2026-11-04T18:00:00Z'), now)).toBe(true)
    expect(isMuted(until('9999-12-31T23:59:59Z'), now)).toBe(true) // "always"
  })
})

describe('header texts', () => {
  it('memberNamesLine: me first as "You", then the others by name', () => {
    expect(memberNamesLine(makeGroup(), ME)).toBe('You, Meena Iyer, Ravi Kumar')
    expect(memberNamesLine(makeGroup({}, { active: false }), ME)).toBe('')
  })

  it('typingText: "typing..." in a private chat, names in a group', () => {
    const group = makeGroup()
    expect(typingText(makeConversation(), [OTHER], ME)).toBe('typing...')
    expect(typingText(group, [], ME)).toBe('')
    expect(typingText(group, [ME], ME)).toBe('') // never my own
    expect(typingText(group, [OTHER], ME)).toBe('Ravi Kumar is typing...')
    expect(typingText(group, [OTHER, THIRD], ME)).toBe('Ravi Kumar and Meena Iyer are typing...')
    const bigger = makeGroup({ participants: [...group.participants, profile('u4', 'Dev Shah')] })
    expect(typingText(bigger, [OTHER, THIRD, 'u4'], ME)).toBe('3 people are typing...')
    expect(typingText(group, ['u-unknown'], ME)).toBe('Someone is typing...')
  })
})

describe('isIncoming', () => {
  it('only real messages from other people count', () => {
    expect(isIncoming(makeMessage({ senderId: OTHER }), ME)).toBe(true)
    expect(isIncoming(makeMessage({ senderId: ME }), ME)).toBe(false)
    expect(isIncoming(makeSystemMessage({ event: 'MEMBER_LEFT', actor: { id: OTHER, name: 'Ravi' } }), ME)).toBe(false)
    expect(isIncoming({ ...makeMessage({ senderId: OTHER }), localStatus: 'SENDING' }, ME)).toBe(false)
  })
})

describe('senderColourClass', () => {
  it('always gives the same colour to the same person', () => {
    expect(senderColourClass('u-a')).toBe(senderColourClass('u-a'))
    expect(senderColourClass('u-a')).toMatch(/^text-/)
  })
})
