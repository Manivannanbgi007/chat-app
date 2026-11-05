import { LogOut, UserPlus, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import Alert from '../common/Alert'
import Avatar from '../common/Avatar'
import ConfirmDialog from '../common/ConfirmDialog'
import DropdownMenu from '../common/DropdownMenu'
import AddMembersDialog from './AddMembersDialog'
import ConversationAvatar from './ConversationAvatar'
import {
  changeMemberRole,
  describeGroupError,
  leaveGroup,
  removeGroupMember,
  updateGroupInfo,
} from '../../features/messaging/chatActions'
import { canEditGroupInfo, isActiveMember, isAdmin } from '../../features/messaging/conversationInfo'
import type { UpdateGroupBody } from '../../services/api/chatApi'
import type { Conversation } from '../../types/chat'
import type { PublicProfile } from '../../types/user'

const MAX_NAME = 100
const MAX_DESCRIPTION = 500

// What needs a "Are you sure?" first.
type Confirmation = { kind: 'remove'; member: PublicProfile } | { kind: 'leave' }

// The group's details: name, description, settings, members and "Exit group".
// Side panel next to the chat on wide screens, full screen on phones.
// What I may do depends on my role: admins manage members and settings; editing the name and description
// is for admins only when the group says so. A former member only sees the name and description.
export default function GroupInfoPanel({
  conversation,
  meId,
  onClose,
}: {
  conversation: Conversation
  meId: string
  onClose: () => void
}) {
  const titleId = useId()
  const group = conversation.group
  const active = isActiveMember(conversation)
  const admin = isAdmin(conversation)
  const canEdit = canEditGroupInfo(conversation)

  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(group?.name ?? '')
  const [description, setDescription] = useState(group?.description ?? '')
  const [addingMembers, setAddingMembers] = useState(false)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Runs one server action with a shared busy flag and error message.
  async function run(action: () => Promise<unknown>): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      await action()
      return true
    } catch (caught) {
      setError(describeGroupError(caught))
      return false
    } finally {
      setBusy(false)
    }
  }

  function startEditing() {
    setName(group?.name ?? '')
    setDescription(group?.description ?? '')
    setEditing(true)
  }

  async function saveInfo(event: FormEvent) {
    event.preventDefault()
    const trimmedName = name.trim()
    if (trimmedName.length < 1 || trimmedName.length > MAX_NAME) {
      setError(`Enter a group name (1-${MAX_NAME} characters).`)
      return
    }
    // Only send what changed.
    const changes: UpdateGroupBody = {}
    if (trimmedName !== group?.name) changes.name = trimmedName
    if (description.trim() !== (group?.description ?? '')) changes.description = description.trim()
    if (Object.keys(changes).length === 0) {
      setEditing(false)
      return
    }
    if (await run(() => updateGroupInfo(conversation.id, changes))) setEditing(false)
  }

  async function confirm() {
    if (!confirmation) return
    const done =
      confirmation.kind === 'remove'
        ? await run(() => removeGroupMember(conversation.id, confirmation.member.id))
        : await run(() => leaveGroup(conversation.id))
    if (done) setConfirmation(null)
  }

  // Admins first, then by name. I am called "You".
  const adminIds = group?.adminIds ?? []
  const members = [...conversation.participants].sort((a, b) => {
    const adminOrder = Number(adminIds.includes(b.id)) - Number(adminIds.includes(a.id))
    return adminOrder !== 0 ? adminOrder : a.displayName.localeCompare(b.displayName)
  })

  return (
    <aside
      aria-labelledby={titleId}
      className="fixed inset-0 z-20 flex flex-col bg-white md:static md:z-auto md:w-80 md:shrink-0 md:border-l md:border-gray-200 dark:bg-gray-800 md:dark:border-gray-700"
    >
      <div className="flex items-center gap-2 border-b border-gray-200 px-3 py-3 dark:border-gray-700">
        <button type="button" onClick={onClose} aria-label="Close group info" className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800">
          <X size={20} />
        </button>
        <h2 id={titleId} className="font-semibold">
          Group info
        </h2>
      </div>

      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">
        {error && <Alert type="error">{error}</Alert>}

        <div className="flex flex-col items-center gap-2 text-center">
          <ConversationAvatar conversation={conversation} size={80} />
          {!editing && (
            <>
              <p className="text-lg font-semibold break-words">{group?.name}</p>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                {active ? `Group · ${conversation.participants.length} members` : 'Group'}
              </p>
            </>
          )}
        </div>

        {!active && <Alert type="info">You're no longer a participant of this group.</Alert>}

        {editing ? (
          <form onSubmit={saveInfo} className="space-y-3" aria-label="Edit group info">
            <label className="block text-sm font-medium">
              Group name
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={MAX_NAME}
                className="mt-1 block w-full rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 font-normal shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
              />
            </label>
            <label className="block text-sm font-medium">
              Description
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={MAX_DESCRIPTION}
                rows={3}
                className="mt-1 block w-full resize-none rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 font-normal shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
              />
            </label>
            <div className="flex justify-end gap-2">
              <button type="button" onClick={() => setEditing(false)} className="rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600">
                Cancel
              </button>
              <button type="submit" disabled={busy} className="rounded bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-50">
                Save
              </button>
            </div>
          </form>
        ) : (
          <section aria-label="Description">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-600 dark:text-gray-300">Description</h3>
              {canEdit && (
                <button type="button" onClick={startEditing} className="text-sm text-brand-700 hover:underline dark:text-brand-300">
                  Edit group info
                </button>
              )}
            </div>
            <p className="mt-1 whitespace-pre-wrap break-words text-sm">
              {group?.description || <span className="text-gray-400">No description</span>}
            </p>
          </section>
        )}

        {admin && (
          <section aria-label="Group settings" className="space-y-2">
            <h3 className="text-sm font-semibold text-gray-600 dark:text-gray-300">Group settings</h3>
            <SettingSwitch
              label="Only admins can send messages"
              checked={group?.onlyAdminsCanSend ?? false}
              disabled={busy}
              onChange={(value) => void run(() => updateGroupInfo(conversation.id, { onlyAdminsCanSend: value }))}
            />
            <SettingSwitch
              label="Only admins can edit group info"
              checked={group?.onlyAdminsCanEdit ?? false}
              disabled={busy}
              onChange={(value) => void run(() => updateGroupInfo(conversation.id, { onlyAdminsCanEdit: value }))}
            />
          </section>
        )}

        {active && (
          <section aria-label="Members">
            <div className="mb-1 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-600 dark:text-gray-300">{members.length} members</h3>
              {admin && (
                <button
                  type="button"
                  onClick={() => setAddingMembers(true)}
                  className="flex items-center gap-1 text-sm text-brand-700 hover:underline dark:text-brand-300"
                >
                  <UserPlus size={16} /> Add members
                </button>
              )}
            </div>
            <ul>
              {members.map((member) => {
                const me = member.id === meId
                const memberIsAdmin = adminIds.includes(member.id)
                const name = me ? 'You' : member.displayName
                return (
                  <li key={member.id} className="flex items-center gap-3 py-2">
                    <Avatar name={member.displayName} imageUrl={member.profileImageUrl} size={32} />
                    <span className="min-w-0 flex-1 truncate text-sm">{name}</span>
                    {memberIsAdmin && (
                      <span className="rounded bg-brand-100 px-1.5 py-0.5 text-xs text-brand-800 dark:bg-brand-900 dark:text-brand-100">
                        Admin
                      </span>
                    )}
                    {admin && !me && (
                      <DropdownMenu
                        label={`Options for ${member.displayName}`}
                        items={[
                          memberIsAdmin
                            ? {
                                label: 'Dismiss as admin',
                                onSelect: () => void run(() => changeMemberRole(conversation.id, member.id, 'MEMBER')),
                              }
                            : {
                                label: 'Make group admin',
                                onSelect: () => void run(() => changeMemberRole(conversation.id, member.id, 'ADMIN')),
                              },
                          { label: 'Remove from group', danger: true, onSelect: () => setConfirmation({ kind: 'remove', member }) },
                        ]}
                      />
                    )}
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        {active && (
          <button
            type="button"
            onClick={() => setConfirmation({ kind: 'leave' })}
            className="flex w-full items-center gap-2 rounded px-2 py-2 text-sm font-medium text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950"
          >
            <LogOut size={18} /> Exit group
          </button>
        )}
      </div>

      {addingMembers && <AddMembersDialog conversation={conversation} onClose={() => setAddingMembers(false)} />}
      {confirmation && (
        <ConfirmDialog
          title={confirmation.kind === 'leave' ? 'Exit group?' : `Remove ${confirmation.member.displayName}?`}
          message={
            confirmation.kind === 'leave'
              ? `You will stop receiving messages from "${group?.name}". You keep the messages you already have.`
              : `${confirmation.member.displayName} will be removed from "${group?.name}".`
          }
          confirmLabel={confirmation.kind === 'leave' ? 'Exit group' : 'Remove'}
          busy={busy}
          onConfirm={() => void confirm()}
          onCancel={() => setConfirmation(null)}
        />
      )}
    </aside>
  )
}

// An on/off switch (role="switch", so screen readers announce "on" / "off").
function SettingSwitch({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (value: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-sm">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${
          checked ? 'bg-brand-600' : 'bg-gray-300 dark:bg-gray-600'
        }`}
      >
        <span
          className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0.5'
          }`}
        />
      </button>
    </div>
  )
}
