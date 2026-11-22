import { create } from 'zustand'
import { readStorage, writeStorage } from '../utils/storage'

// theme: 'light' | 'dark'

const STORAGE_KEY = 'chat-app-theme'

// Add or remove the "dark" class on <html>. Tailwind reads it.
function applyTheme(theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

// A saved choice wins; otherwise follow the system setting (dark system = dark app).
function loadTheme() {
  const saved = readStorage(STORAGE_KEY)
  if (saved === 'dark' || saved === 'light') return saved
  const systemIsDark = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
  return systemIsDark ? 'dark' : 'light'
}

const initialTheme = loadTheme()
applyTheme(initialTheme)

export const useThemeStore = create((set, get) => ({
  theme: initialTheme,
  toggleTheme: () => {
    const next = get().theme === 'dark' ? 'light' : 'dark'
    applyTheme(next)
    writeStorage(STORAGE_KEY, next)
    set({ theme: next })
  },
}))
