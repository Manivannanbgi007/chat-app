import { ME, OTHER, THIRD, makeGroup, makeMessage, makeSystemMessage } from '../../test/chatFixtures'
import type { SystemContent } from '../../types/chat'
import { GENERIC_SYSTEM_TEXT, describeSystemMessage, parseSystemContent, previewText } from './systemMessages'

const group = makeGroup() // members: me (Asha Rao), OTHER (Ravi Kumar), THIRD (Meena Iyer)
const asha = { id: ME, name: 'Asha Rao' }
const ravi = { id: OTHER, name: 'Ravi Kumar' }
const meena = { id: THIRD, name: 'Meena Iyer' }
const gone = { id: 'u-gone', name: 'Old Friend' } // not a current member any more

function text(content: SystemContent, conversation = group) {
  return describeSystemMessage(makeSystemMessage(content), conversation, ME)
}

describe('describeSystemMessage', () => {
  it.each<[SystemContent, string]>([
    [{ event: 'GROUP_CREATED', actor: asha, name: 'Trip' }, 'You created group "Trip"'],
    [{ event: 'GROUP_CREATED', actor: ravi, name: 'Trip' }, 'Ravi Kumar created group "Trip"'],
    [{ event: 'MEMBERS_ADDED', actor: asha, users: [ravi, meena] }, 'You added Ravi Kumar and Meena Iyer'],
    [{ event: 'MEMBERS_ADDED', actor: ravi, users: [asha] }, 'Ravi Kumar added you'],
    [{ event: 'MEMBERS_ADDED', actor: ravi, users: [asha, meena, gone] }, 'Ravi Kumar added you, Meena Iyer and Old Friend'],
    [{ event: 'MEMBER_REMOVED', actor: ravi, users: [asha] }, 'Ravi Kumar removed you'],
    [{ event: 'MEMBER_REMOVED', actor: asha, users: [gone] }, 'You removed Old Friend'],
    [{ event: 'MEMBER_LEFT', actor: ravi }, 'Ravi Kumar left'],
    [{ event: 'MEMBER_LEFT', actor: asha }, 'You left'],
    [{ event: 'ADMIN_ADDED', actor: asha, users: [ravi] }, 'You made Ravi Kumar an admin'],
    [{ event: 'ADMIN_ADDED', actor: ravi, users: [asha] }, 'Ravi Kumar made you an admin'],
    [{ event: 'ADMIN_ADDED', actor: ravi, users: [asha, meena] }, 'Ravi Kumar made you and Meena Iyer admins'],
    [{ event: 'ADMIN_ADDED', actor: null, users: [meena] }, 'Meena Iyer is now an admin'],
    [{ event: 'ADMIN_ADDED', actor: null, users: [asha] }, "You're now an admin"],
    [{ event: 'ADMIN_REMOVED', actor: ravi, users: [meena] }, 'Ravi Kumar dismissed Meena Iyer as admin'],
    [{ event: 'NAME_CHANGED', actor: ravi, name: 'Goa 2027' }, 'Ravi Kumar changed the group name to "Goa 2027"'],
    [{ event: 'DESCRIPTION_CHANGED', actor: asha }, 'You changed the group description'],
    [
      { event: 'SETTINGS_CHANGED', actor: ravi, setting: 'ONLY_ADMINS_CAN_SEND', value: true },
      "Ravi Kumar changed this group's settings to allow only admins to send messages",
    ],
    [
      { event: 'SETTINGS_CHANGED', actor: ravi, setting: 'ONLY_ADMINS_CAN_SEND', value: false },
      "Ravi Kumar changed this group's settings to allow all members to send messages",
    ],
    [
      { event: 'SETTINGS_CHANGED', actor: asha, setting: 'ONLY_ADMINS_CAN_EDIT', value: true },
      "You changed this group's settings to allow only admins to edit this group's info",
    ],
    [
      { event: 'SETTINGS_CHANGED', actor: asha, setting: 'ONLY_ADMINS_CAN_EDIT', value: false },
      "You changed this group's settings to allow all members to edit this group's info",
    ],
  ])('%j -> %s', (content, expected) => {
    expect(text(content)).toBe(expected)
  })

  it('prefers the current name, then the stored name, then "Someone"', () => {
    const renamed = { ...group, participants: group.participants.map((p) => (p.id === OTHER ? { ...p, displayName: 'Ravi K.' } : p)) }
    expect(text({ event: 'MEMBER_LEFT', actor: ravi }, renamed)).toBe('Ravi K. left') // current name wins
    expect(text({ event: 'MEMBER_LEFT', actor: gone })).toBe('Old Friend left') // stored name
    expect(text({ event: 'MEMBER_LEFT', actor: { id: 'u-x', name: null } })).toBe('Someone left')
    expect(text({ event: 'MEMBERS_ADDED', actor: ravi, users: [{ id: 'u-y', name: null }] })).toBe('Ravi Kumar added someone')
  })

  it('works without the conversation (stored names only)', () => {
    expect(describeSystemMessage(makeSystemMessage({ event: 'MEMBER_LEFT', actor: ravi }), undefined, ME)).toBe('Ravi Kumar left')
  })

  it('falls back to a generic text for content it cannot understand', () => {
    const broken = [
      'not json',
      '42',
      '{"event":"SOMETHING_NEW","actor":{"id":"x","name":"X"}}',
      '{"event":"MEMBER_LEFT"}', // no actor
      '{"event":"MEMBER_LEFT","actor":{"name":"no id"}}',
    ]
    for (const content of broken) {
      expect(describeSystemMessage(makeMessage({ type: 'SYSTEM', content }), group, ME)).toBe(GENERIC_SYSTEM_TEXT)
    }
  })
})

describe('parseSystemContent', () => {
  it('ignores broken entries inside the users list', () => {
    const parsed = parseSystemContent('{"event":"MEMBERS_ADDED","actor":{"id":"a","name":"A"},"users":[{"id":"b","name":"B"},7,{"x":1}]}')
    expect(parsed).toMatchObject({ event: 'MEMBERS_ADDED', users: [{ id: 'b', name: 'B' }] })
  })
})

describe('previewText', () => {
  it('uses the sentence for SYSTEM messages and the text for normal ones', () => {
    expect(previewText(makeSystemMessage({ event: 'MEMBER_LEFT', actor: ravi }), group, ME)).toBe('Ravi Kumar left')
    expect(previewText(makeMessage({ content: 'hello there' }), group, ME)).toBe('hello there')
  })
})

describe('previewText for media messages', () => {
  const media = (type: 'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT', content = '') => makeMessage({ type, content })

  it('uses a label for each kind', () => {
    expect(previewText(media('IMAGE'), group, ME)).toBe('Photo')
    expect(previewText(media('VIDEO'), group, ME)).toBe('Video')
    expect(previewText(media('AUDIO'), group, ME)).toBe('Audio')
    expect(previewText(media('VOICE'), group, ME)).toBe('Voice message')
    expect(previewText(media('DOCUMENT'), group, ME)).toBe('Document')
  })

  it('adds the caption when there is one', () => {
    expect(previewText(media('IMAGE', 'Sunset'), group, ME)).toBe('Photo: Sunset')
    expect(previewText(media('DOCUMENT', '   '), group, ME)).toBe('Document')
  })
})
