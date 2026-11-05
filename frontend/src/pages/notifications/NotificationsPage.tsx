import PageHeader from '../../components/common/PageHeader'
import NotificationList from '../../components/notifications/NotificationList'

// /notifications: the same list as the bell's panel, as a page (used on phones).
export default function NotificationsPage() {
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="Notifications" subtitle="Missed messages, calls and group changes." />
      <section className="card overflow-hidden">
        <NotificationList />
      </section>
    </div>
  )
}
