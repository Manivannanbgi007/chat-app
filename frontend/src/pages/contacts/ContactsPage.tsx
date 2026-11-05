import { useState } from 'react'
import BlockedTab from '../../components/contacts/BlockedTab'
import FindPeopleTab from '../../components/contacts/FindPeopleTab'
import MyContactsTab from '../../components/contacts/MyContactsTab'
import ProfileDialog from '../../components/contacts/ProfileDialog'
import PageHeader from '../../components/common/PageHeader'

type Tab = 'contacts' | 'find' | 'blocked'

const TABS: { id: Tab; label: string }[] = [
  { id: 'contacts', label: 'My contacts' },
  { id: 'find', label: 'Find people' },
  { id: 'blocked', label: 'Blocked' },
]

export default function ContactsPage() {
  const [tab, setTab] = useState<Tab>('contacts')
  // The user whose profile pop-up is open (null = closed).
  const [viewedUserId, setViewedUserId] = useState<string | null>(null)

  return (
    <section>
      <PageHeader title="Contacts" subtitle="People you can chat and call. Find new people or manage who is blocked." />

      <div role="tablist" className="mb-6 inline-flex gap-1 rounded-2xl bg-gray-100 p-1 dark:bg-gray-800">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            onClick={() => setTab(item.id)}
            className={`rounded-xl px-4 py-2 text-sm font-medium transition-all ${
              tab === item.id
                ? 'bg-white text-brand-700 shadow-sm dark:bg-gray-900 dark:text-brand-300'
                : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'contacts' && <MyContactsTab onView={setViewedUserId} />}
      {tab === 'find' && <FindPeopleTab onView={setViewedUserId} />}
      {tab === 'blocked' && <BlockedTab onView={setViewedUserId} />}

      {viewedUserId && <ProfileDialog userId={viewedUserId} onClose={() => setViewedUserId(null)} />}
    </section>
  )
}
