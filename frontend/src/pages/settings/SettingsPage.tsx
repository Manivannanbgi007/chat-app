import { LogOut } from 'lucide-react'
import PageHeader from '../../components/common/PageHeader'
import { useNavigate } from 'react-router-dom'
import { logoutUser } from '../../features/authentication/authActions'
import { useThemeStore } from '../../store/themeStore'
import DevicesSection from './DevicesSection'
import NotificationsSection from './NotificationsSection'
import PrivacySection from './PrivacySection'
import StatusPrivacySection from './StatusPrivacySection'

export default function SettingsPage() {
  const navigate = useNavigate()
  const theme = useThemeStore((state) => state.theme)
  const toggleTheme = useThemeStore((state) => state.toggleTheme)

  async function handleLogout() {
    await logoutUser()
    navigate('/login', { replace: true })
  }

  return (
    <div>
      <PageHeader title="Settings" subtitle="Privacy, notifications, appearance and your devices." />

      {/* On big screens the sections flow into 2 or 3 columns, so the whole window is used. */}
      <div className="gap-6 xl:columns-2 2xl:columns-3 [&>*]:mb-6 [&>*]:break-inside-avoid">
        <PrivacySection />

        <StatusPrivacySection />

        <section className="card p-6">
          <h2 className="mb-4 text-lg font-semibold">Appearance</h2>
          <div role="radiogroup" aria-label="Theme" className="flex gap-2">
            {(['light', 'dark'] as const).map((option) => (
              <button
                key={option}
                type="button"
                role="radio"
                aria-checked={theme === option}
                onClick={() => theme !== option && toggleTheme()}
                className={`flex-1 rounded-xl border px-3 py-2 text-sm font-medium capitalize transition ${
                  theme === option
                    ? 'border-brand-600 bg-brand-50 text-brand-700 shadow-sm dark:bg-brand-950 dark:text-brand-300'
                    : 'border-gray-200 hover:border-brand-300 dark:border-gray-700'
                }`}
              >
                {option}
              </button>
            ))}
          </div>
        </section>

        <DevicesSection />

        <NotificationsSection />

        <div>
          <button
            type="button"
            onClick={handleLogout}
            className="flex items-center gap-2 rounded-xl border border-red-300 px-4 py-2.5 font-medium text-red-600 hover:bg-red-50 dark:border-red-800 dark:hover:bg-red-950"
          >
            <LogOut size={18} />
            Log out
          </button>
        </div>
      </div>
    </div>
  )
}
