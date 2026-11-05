import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import * as chatApi from '../../services/api/chatApi'
import { AppError } from '../../services/api/appError'
import { getContacts } from '../../services/api/userApi'
import { useChatStore } from '../../store/chatStore'
import { makeGroup, profile } from '../../test/chatFixtures'
import LocationDisplay from '../../test/LocationDisplay'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Contact } from '../../types/user'
import NewGroupDialog from './NewGroupDialog'

vi.mock('../../services/api/chatApi')
vi.mock('../../services/api/userApi')
const api = vi.mocked(chatApi)

function contact(id: string, name: string): Contact {
  return { id: `contact-${id}`, contactUserId: id, nickname: null, createdAt: '2026-11-01T00:00:00Z', user: profile(id, name) }
}

const onClose = vi.fn()

function renderDialog() {
  renderWithProviders(
    <Routes>
      <Route path="/chats" element={<NewGroupDialog onClose={onClose} />} />
      <Route path="*" element={<LocationDisplay />} />
    </Routes>,
    { route: '/chats' },
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  useChatStore.getState().reset()
  vi.mocked(getContacts).mockResolvedValue([
    contact('u-ravi', 'Ravi Kumar'),
    contact('u-meena', 'Meena Iyer'),
    contact('u-dev', 'Dev Shah'),
  ])
})

async function choose(user: ReturnType<typeof userEvent.setup>, ...names: string[]) {
  for (const name of names) {
    await user.click(await screen.findByRole('checkbox', { name: new RegExp(name) }))
  }
}

describe('NewGroupDialog', () => {
  it('step 1: pick people (with a filter and chips); Next needs at least one', async () => {
    renderDialog()
    const user = userEvent.setup()

    expect(screen.getByRole('dialog', { name: 'New group: add members' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()

    await user.type(await screen.findByLabelText('Search contacts'), 'mee')
    expect(within(screen.getByRole('list', { name: 'Contacts' })).getAllByRole('checkbox')).toHaveLength(1)
    await choose(user, 'Meena Iyer')
    await user.clear(screen.getByLabelText('Search contacts'))

    expect(within(screen.getByRole('list', { name: 'Selected people' })).getByText('Meena Iyer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Remove Meena Iyer' }))
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('step 2: the name is required; creating opens the new group', async () => {
    api.createGroup.mockResolvedValue(makeGroup({ id: 'g-new' }))
    renderDialog()
    const user = userEvent.setup()

    await choose(user, 'Ravi Kumar', 'Dev Shah')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByRole('dialog', { name: 'New group: name' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Create group' }))
    expect(screen.getByText(/Enter a group name/)).toBeInTheDocument()
    expect(api.createGroup).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Group name'), '  Weekend hike ')
    await user.type(screen.getByLabelText('Description (optional)'), 'Saturday 7am')
    await user.click(screen.getByRole('button', { name: 'Create group' }))

    expect(api.createGroup).toHaveBeenCalledWith({
      name: 'Weekend hike',
      description: 'Saturday 7am',
      memberIds: ['u-ravi', 'u-dev'],
    })
    expect(await screen.findByTestId('path')).toHaveTextContent('/chats/g-new')
    expect(onClose).toHaveBeenCalled()
    expect(useChatStore.getState().conversations['g-new']).toBeDefined()
  })

  it('people refused by their privacy settings are marked; untick them and try again', async () => {
    api.createGroup.mockRejectedValueOnce(
      new AppError('FORBIDDEN', 'no', 403, {}, 'GROUP_ADD_NOT_ALLOWED', { errorCode: 'GROUP_ADD_NOT_ALLOWED', userIds: ['u-meena'] }),
    )
    api.createGroup.mockResolvedValueOnce(makeGroup({ id: 'g-2' }))
    renderDialog()
    const user = userEvent.setup()

    await choose(user, 'Ravi Kumar', 'Meena Iyer')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.type(screen.getByLabelText('Group name'), 'Trip')
    await user.click(screen.getByRole('button', { name: 'Create group' }))

    // back on step 1, with an explanation and Meena marked
    expect(await screen.findByRole('dialog', { name: 'New group: add members' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/privacy settings/)
    expect(screen.getByText("can't be added (privacy settings)")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: /Meena Iyer/ }))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByLabelText('Group name')).toHaveValue('Trip') // kept
    await user.click(screen.getByRole('button', { name: 'Create group' }))

    expect(api.createGroup).toHaveBeenLastCalledWith({ name: 'Trip', memberIds: ['u-ravi'] })
    expect(await screen.findByTestId('path')).toHaveTextContent('/chats/g-2')
  })

  it('explains a full group', async () => {
    api.createGroup.mockRejectedValue(new AppError('UNKNOWN', 'full', 409, {}, 'GROUP_FULL'))
    renderDialog()
    const user = userEvent.setup()

    await choose(user, 'Ravi Kumar')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.type(screen.getByLabelText('Group name'), 'Big')
    await user.click(screen.getByRole('button', { name: 'Create group' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('at most 256 members')
  })

  it('says so when there are no contacts', async () => {
    vi.mocked(getContacts).mockResolvedValue([])
    renderDialog()

    expect(await screen.findByText(/No contacts to choose from/)).toBeInTheDocument()
  })
})
