import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import * as chatApi from '../../services/api/chatApi'
import { AppError } from '../../services/api/appError'
import { getContacts } from '../../services/api/userApi'
import { useChatStore } from '../../store/chatStore'
import { ME, OTHER, makeGroup, profile } from '../../test/chatFixtures'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Conversation } from '../../types/chat'
import type { Contact } from '../../types/user'
import GroupInfoPanel from './GroupInfoPanel'

vi.mock('../../services/api/chatApi')
vi.mock('../../services/api/userApi')
const api = vi.mocked(chatApi)
const onClose = vi.fn()

// Like ChatWindow: the panel always shows the conversation from the store, so it updates after every action.
function Panel() {
  const conversation = useChatStore((state) => state.conversations['g1'])
  return conversation ? <GroupInfoPanel conversation={conversation} meId={ME} onClose={onClose} /> : null
}

function renderPanel(conversation: Conversation) {
  useChatStore.setState({ conversations: { g1: conversation } })
  renderWithProviders(<Panel />)
}

function contact(id: string, name: string): Contact {
  return { id: `contact-${id}`, contactUserId: id, nickname: null, createdAt: '2026-11-01T00:00:00Z', user: profile(id, name) }
}

beforeEach(() => {
  vi.resetAllMocks()
  useChatStore.getState().reset()
})

describe('GroupInfoPanel as an admin', () => {
  it('shows the group, its members (admins first, me as "You") and every admin control', () => {
    renderPanel(makeGroup())

    expect(screen.getByRole('complementary', { name: 'Group info' })).toBeInTheDocument()
    expect(screen.getByText('Trip to Goa')).toBeInTheDocument()
    expect(screen.getByText('Group · 3 members')).toBeInTheDocument()
    expect(screen.getByText('Planning the trip')).toBeInTheDocument()

    const members = within(screen.getByRole('region', { name: 'Members' })).getAllByRole('listitem')
    // (the text also contains the avatar initials, e.g. "AR")
    expect(members.map((item) => item.textContent)).toEqual(['ARYouAdmin', 'MIMeena Iyer', 'RKRavi Kumar'])

    expect(screen.getByRole('button', { name: 'Edit group info' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Only admins can send messages' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('switch', { name: 'Only admins can edit group info' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add members' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Options for Ravi Kumar' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Options for Asha Rao' })).not.toBeInTheDocument() // not for myself
    expect(screen.getByRole('button', { name: 'Exit group' })).toBeInTheDocument()
  })

  it('makes someone admin', async () => {
    const group = makeGroup()
    api.setMemberRole.mockResolvedValue({ ...group, group: { ...group.group!, adminIds: [ME, OTHER] } })
    renderPanel(group)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Options for Ravi Kumar' }))
    await user.click(screen.getByRole('menuitem', { name: 'Make group admin' }))

    expect(api.setMemberRole).toHaveBeenCalledWith('g1', OTHER, 'ADMIN')
    await user.click(await screen.findByRole('button', { name: 'Options for Ravi Kumar' }))
    expect(screen.getByRole('menuitem', { name: 'Dismiss as admin' })).toBeInTheDocument()
  })

  it('removes a member only after confirming', async () => {
    const group = makeGroup()
    api.removeMember.mockResolvedValue({ ...group, participants: group.participants.filter((p) => p.id !== OTHER) })
    renderPanel(group)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Options for Ravi Kumar' }))
    await user.click(screen.getByRole('menuitem', { name: 'Remove from group' }))
    expect(screen.getByRole('alertdialog', { name: 'Remove Ravi Kumar?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(api.removeMember).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Options for Ravi Kumar' }))
    await user.click(screen.getByRole('menuitem', { name: 'Remove from group' }))
    await user.click(screen.getByRole('button', { name: 'Remove' }))

    expect(api.removeMember).toHaveBeenCalledWith('g1', OTHER)
    expect(await screen.findByText('Group · 2 members')).toBeInTheDocument()
  })

  it('switches a setting', async () => {
    const group = makeGroup()
    api.updateGroup.mockResolvedValue(makeGroup({}, { onlyAdminsCanSend: true }))
    renderPanel(group)
    const user = userEvent.setup()

    await user.click(screen.getByRole('switch', { name: 'Only admins can send messages' }))

    expect(api.updateGroup).toHaveBeenCalledWith('g1', { onlyAdminsCanSend: true })
    expect(await screen.findByRole('switch', { name: 'Only admins can send messages' })).toHaveAttribute('aria-checked', 'true')
  })

  it('edits the name and sends only what changed', async () => {
    const group = makeGroup()
    api.updateGroup.mockResolvedValue({ ...group, group: { ...group.group!, name: 'Goa 2027' } })
    renderPanel(group)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Edit group info' }))
    await user.clear(screen.getByLabelText('Group name'))
    await user.type(screen.getByLabelText('Group name'), 'Goa 2027')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(api.updateGroup).toHaveBeenCalledWith('g1', { name: 'Goa 2027' })
    expect(await screen.findByText('Goa 2027')).toBeInTheDocument()
  })

  it('adds members from my contacts (current members are not offered)', async () => {
    vi.mocked(getContacts).mockResolvedValue([contact(OTHER, 'Ravi Kumar'), contact('u-dev', 'Dev Shah')])
    api.addMembers.mockResolvedValue(makeGroup())
    renderPanel(makeGroup())
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Add members' }))
    const dialog = screen.getByRole('dialog', { name: 'Add members' })
    expect(await within(dialog).findByRole('checkbox', { name: /Dev Shah/ })).toBeInTheDocument()
    expect(within(dialog).queryByRole('checkbox', { name: /Ravi Kumar/ })).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('checkbox', { name: /Dev Shah/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Add to group' }))

    expect(api.addMembers).toHaveBeenCalledWith('g1', ['u-dev'])
    expect(screen.queryByRole('dialog', { name: 'Add members' })).not.toBeInTheDocument()
  })

  it('shows server errors inline', async () => {
    api.setMemberRole.mockRejectedValue(new AppError('FORBIDDEN', 'no', 403, {}, 'NOT_GROUP_ADMIN'))
    api.getConversation.mockResolvedValue(makeGroup())
    renderPanel(makeGroup())
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Options for Ravi Kumar' }))
    await user.click(screen.getByRole('menuitem', { name: 'Make group admin' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Only group admins can do that.')
  })
})

describe('GroupInfoPanel as a member', () => {
  it('hides the admin controls', () => {
    renderPanel(makeGroup({}, { role: 'MEMBER', onlyAdminsCanEdit: true }))

    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add members' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Options for/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit group info' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Exit group' })).toBeInTheDocument()
    // Ravi is the admin of this group
    expect(within(screen.getByRole('region', { name: 'Members' })).getByText('Admin')).toBeInTheDocument()
  })

  it('may edit the group info when the group allows everybody to', () => {
    renderPanel(makeGroup({}, { role: 'MEMBER', onlyAdminsCanEdit: false }))

    expect(screen.getByRole('button', { name: 'Edit group info' })).toBeInTheDocument()
  })

  it('exits the group after confirming, then sees the former-member view', async () => {
    api.leaveGroup.mockResolvedValue(makeGroup({}, { active: false }))
    renderPanel(makeGroup({}, { role: 'MEMBER' }))
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Exit group' }))
    expect(screen.getByRole('alertdialog', { name: 'Exit group?' })).toBeInTheDocument()
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Exit group' }))

    expect(api.leaveGroup).toHaveBeenCalledWith('g1')
    expect(await screen.findByText("You're no longer a participant of this group.")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Exit group' })).not.toBeInTheDocument()
  })
})

describe('GroupInfoPanel for a former member', () => {
  it('shows only the name and description', () => {
    renderPanel(makeGroup({}, { active: false }))

    expect(screen.getByText('Trip to Goa')).toBeInTheDocument()
    expect(screen.getByText("You're no longer a participant of this group.")).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Members' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Exit group' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit group info' })).not.toBeInTheDocument()
  })

  it('closes', async () => {
    renderPanel(makeGroup({}, { active: false }))

    await userEvent.setup().click(screen.getByRole('button', { name: 'Close group info' }))

    expect(onClose).toHaveBeenCalled()
  })
})
