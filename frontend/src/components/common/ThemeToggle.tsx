import { Moon, Sun } from 'lucide-react'
import { useThemeStore } from '../../store/themeStore'

// Button that switches between light and dark mode. The icon turns a little when it changes.
export default function ThemeToggle({ className = '' }: { className?: string }) {
  const theme = useThemeStore((state) => state.theme)
  const toggleTheme = useThemeStore((state) => state.toggleTheme)

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
      className={`group rounded-xl p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100 ${className}`}
    >
      <span key={theme} className="block animate-pop transition-transform duration-300 group-hover:rotate-12">
        {theme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}
      </span>
    </button>
  )
}
