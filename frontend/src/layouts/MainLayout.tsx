import { Lock, MessageCircle, Video, Zap } from 'lucide-react'
import { Link, Outlet } from 'react-router-dom'
import Brand from '../components/common/Brand'
import ThemeToggle from '../components/common/ThemeToggle'

const FEATURES = [
  { Icon: MessageCircle, text: 'Real-time chats and groups' },
  { Icon: Video, text: 'Voice and video calls' },
  { Icon: Zap, text: 'Photos, files and status updates' },
  { Icon: Lock, text: 'You decide who sees what' },
]

// The frame of the login / register / password pages: a colourful brand panel on the left (big screens only) and the
// form centred on the right. It fills the whole browser window.
export default function MainLayout() {
  return (
    <div className="bg-aurora flex h-dvh w-full overflow-hidden">
      <section
        aria-hidden="true"
        className="relative hidden w-[46%] max-w-3xl shrink-0 flex-col justify-between overflow-hidden bg-gradient-to-br from-brand-600 via-brand-700 to-brand-950 p-12 text-white lg:flex"
      >
        {/* floating shapes */}
        <span className="absolute -left-24 top-1/3 h-72 w-72 animate-float rounded-full bg-white/10 blur-2xl" />
        <span className="absolute -right-16 bottom-10 h-80 w-80 animate-float rounded-full bg-sky-400/20 blur-3xl [animation-delay:-2s]" />
        <span className="absolute left-1/2 top-10 h-40 w-40 animate-float rounded-full bg-fuchsia-400/20 blur-2xl [animation-delay:-3.5s]" />

        <div className="relative flex items-center gap-3 text-xl font-bold tracking-tight">
          <Brand size={44} />
          Chat App
        </div>

        <div className="relative">
          <h2 className="mb-3 text-4xl font-bold leading-tight tracking-tight xl:text-5xl">Stay close to the people who matter.</h2>
          <p className="mb-10 max-w-md text-lg text-white/75">Fast, private messaging for friends, family and teams.</p>
          <ul className="space-y-4">
            {FEATURES.map(({ Icon, text }, index) => (
              <li key={text} className="flex animate-slide-in items-center gap-3 text-white/90" style={{ animationDelay: `${300 + index * 120}ms` }}>
                <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/15 backdrop-blur">
                  <Icon size={20} />
                </span>
                {text}
              </li>
            ))}
          </ul>
        </div>

        {/* a few chat bubbles for decoration */}
        <div className="relative flex flex-col gap-2 text-sm">
          <span className="max-w-[16rem] animate-bubble-in rounded-2xl rounded-bl-md bg-white/15 px-4 py-2.5 backdrop-blur [animation-delay:900ms]">Are we still on for tonight?</span>
          <span className="ml-auto max-w-[16rem] animate-bubble-in rounded-2xl rounded-br-md bg-white px-4 py-2.5 text-brand-800 [animation-delay:1200ms]">Absolutely. See you at 7!</span>
        </div>
      </section>

      <div className="relative flex min-w-0 flex-1 flex-col overflow-y-auto">
        <header className="flex shrink-0 items-center justify-between p-4 sm:p-6">
          <Link to="/" className="flex items-center gap-2 font-bold tracking-tight lg:invisible">
            <Brand size={34} />
            Chat App
          </Link>
          <ThemeToggle />
        </header>
        <main className="flex flex-1 items-center justify-center p-4 pb-10 sm:p-8">
          <div className="w-full max-w-md">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
