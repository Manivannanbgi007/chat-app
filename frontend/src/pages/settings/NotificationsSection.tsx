import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import Toggle from '../../components/common/Toggle'
import {
  desktopPermission,
  isDesktopChosen,
  setDesktopChosen,
} from '../../features/notifications/localNotifications'
import { toAppError } from '../../services/api/appError'
import { getNotificationPreferences, updateNotificationPreferences } from '../../services/api/notificationApi'
import { useNotificationStore } from '../../store/notificationStore'
import type { NotificationPreferences } from '../../types/notification'

const PREFERENCES_KEY = ['notification-preferences']

const TOGGLES: { name: keyof NotificationPreferences; label: string; hint?: string }[] = [
  { name: 'pushEnabled', label: 'Push notifications', hint: 'Sent to your devices when you are offline.' },
  { name: 'messagesEnabled', label: 'New messages in private chats' },
  { name: 'groupsEnabled', label: 'Groups', hint: 'Group messages and changes (added, removed, made admin).' },
  { name: 'callsEnabled', label: 'Missed calls', hint: 'A notification when you miss a voice or video call.' },
]

// Settings -> Notifications: what the server may notify me about, plus the pop-ups on THIS device.
export default function NotificationsSection() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const sectionRef = useRef<HTMLElement>(null)
  const preferencesQuery = useQuery({ queryKey: PREFERENCES_KEY, queryFn: getNotificationPreferences })

  // Whatever the server says is also what the rest of the app uses.
  const loadedPreferences = preferencesQuery.data
  useEffect(() => {
    if (loadedPreferences) useNotificationStore.getState().setPreferences(loadedPreferences)
  }, [loadedPreferences])

  // Reachable as /settings#notifications (the link in the bell panel)
  useEffect(() => {
    if (location.hash === '#notifications' && loadedPreferences) sectionRef.current?.scrollIntoView?.({ block: 'start' })
  }, [location.hash, loadedPreferences])

  // Saving is optimistic: the switch moves at once and jumps back if the server refuses.
  const saveMutation = useMutation({
    mutationFn: (next: NotificationPreferences) => updateNotificationPreferences(next),
    onMutate: async (next) => {
      await queryClient.cancelQueries({ queryKey: PREFERENCES_KEY })
      const previous = queryClient.getQueryData<NotificationPreferences>(PREFERENCES_KEY)
      queryClient.setQueryData(PREFERENCES_KEY, next)
      useNotificationStore.getState().setPreferences(next) // the bell panel and the pop-ups follow at once
      return { previous }
    },
    onError: (_error, _next, context) => {
      if (context?.previous) {
        queryClient.setQueryData(PREFERENCES_KEY, context.previous)
        useNotificationStore.getState().setPreferences(context.previous)
      }
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(PREFERENCES_KEY, saved)
      useNotificationStore.getState().setPreferences(saved)
    },
  })

  function toggle(name: keyof NotificationPreferences, value: boolean) {
    if (!preferencesQuery.data) return
    saveMutation.mutate({ ...preferencesQuery.data, [name]: value })
  }

  return (
    <section ref={sectionRef} id="notifications" className="scroll-mt-4 card p-6">
      <h2 className="mb-4 text-lg font-semibold">Notifications</h2>

      {preferencesQuery.isPending && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Loading...
        </p>
      )}
      {preferencesQuery.error && <Alert type="error">{toAppError(preferencesQuery.error).message}</Alert>}
      {saveMutation.error && (
        <div className="mb-3">
          <Alert type="error">{toAppError(saveMutation.error).message}</Alert>
        </div>
      )}

      {preferencesQuery.data && (
        <ul className="mb-6 divide-y divide-gray-100 rounded-xl border border-gray-200/80 dark:divide-gray-800 dark:border-gray-800">
          {TOGGLES.map((item) => (
            <li key={item.name} className="px-4 py-3">
              <Toggle checked={preferencesQuery.data[item.name]} onChange={(value) => toggle(item.name, value)} hint={item.hint}>
                {item.label}
              </Toggle>
            </li>
          ))}
        </ul>
      )}

      <DesktopNotificationsToggle />
    </section>
  )
}

// "Desktop notifications on this device": a pop-up of the operating system for new messages while this tab is hidden.
// The choice is only remembered in this browser; the browser itself must also allow it.
function DesktopNotificationsToggle() {
  const [chosen, setChosen] = useState(isDesktopChosen)
  const [permission, setPermission] = useState(desktopPermission)
  const [message, setMessage] = useState<string | null>(null)

  const unsupported = permission === 'unsupported'
  const denied = permission === 'denied'
  const on = chosen && permission === 'granted'

  async function handleChange(wanted: boolean) {
    setMessage(null)
    if (!wanted) {
      setDesktopChosen(false)
      setChosen(false)
      return
    }
    let result = permission
    if (result === 'default') {
      try {
        result = await Notification.requestPermission()
      } catch {
        result = 'denied'
      }
      setPermission(result)
    }
    if (result === 'granted') {
      setDesktopChosen(true)
      setChosen(true)
    } else {
      setDesktopChosen(false)
      setChosen(false)
      setMessage('Desktop notifications were not allowed, so they stay off.')
    }
  }

  return (
    <div>
      <h3 className="mb-2 font-semibold">Desktop notifications on this device</h3>
      <div className="rounded-xl border border-gray-200/80 px-4 py-3 dark:border-gray-800">
        <Toggle
          checked={on}
          disabled={unsupported || denied}
          onChange={(value) => void handleChange(value)}
          hint="Only works while the app is open in a browser tab. Muted chats never show pop-ups, and the switches above apply too."
        >
          Show a pop-up for new messages while this tab is hidden
        </Toggle>
      </div>
      {unsupported && (
        <p className="mt-2 text-sm text-gray-600 dark:text-gray-300">This browser does not support desktop notifications.</p>
      )}
      {denied && (
        <p className="mt-2 text-sm text-gray-600 dark:text-gray-300">
          Notifications are blocked for this site in your browser settings. Allow them there, then come back and switch
          this on.
        </p>
      )}
      {message && (
        <p role="status" className="mt-2 text-sm text-gray-600 dark:text-gray-300">
          {message}
        </p>
      )}
    </div>
  )
}
