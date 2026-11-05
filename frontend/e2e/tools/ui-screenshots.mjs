// Takes screenshots of every main screen, in several window sizes and in light and dark mode, so a UI change can be
// looked at (and compared before / after) without clicking through the app.
//
//   node e2e/tools/ui-screenshots.mjs                 # all screens, into ./ui-screenshots/<label>/
//   node e2e/tools/ui-screenshots.mjs after desktop   # label "after", only the "desktop" size
//
// Needs the stack running and the demo accounts of infra/DEMO_ACCOUNTS.md (the file is read here, the password is
// never printed). Uses the Google Chrome that is installed (no download), like the Playwright tests.

import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const label = process.argv[2] ?? 'current'
const onlySize = process.argv[3]
const out = join('ui-screenshots', label)
mkdirSync(out, { recursive: true })

const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const emails = Object.fromEntries([...accounts.matchAll(/\| (\w+) Demo \| (\S+@example\.com)/g)].map((m) => [m[1].toLowerCase(), m[2]]))

const SIZES = {
  desktop: { width: 1920, height: 1080 },
  laptop: { width: 1366, height: 768 },
  tablet: { width: 820, height: 1180 },
  phone: { width: 390, height: 844 },
}

async function login(page, who) {
  await page.goto(`${BASE}/login`)
  await page.getByLabel('Email or phone number').fill(emails[who])
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in' }).click()
  await page.waitForURL(/\/chats/)
}

const browser = await chromium.launch({ channel: 'chrome', args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] })
for (const [sizeName, viewport] of Object.entries(SIZES)) {
  if (onlySize && onlySize !== sizeName) continue
  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ viewport, colorScheme: theme })
    await context.addInitScript((t) => {
      try { localStorage.setItem('chat-app-theme', t) } catch { /* ignore */ }
    }, theme)
    const page = await context.newPage()
    const shot = async (name) => {
      await page.waitForTimeout(900) // let animations finish
      await page.screenshot({ path: join(out, `${sizeName}-${theme}-${name}.png`) })
    }

    await page.goto(`${BASE}/login`)
    await shot('login')
    await page.goto(`${BASE}/register`)
    await shot('register')

    await login(page, 'alice')
    await page.waitForTimeout(800)
    await shot('chats')
    const firstChat = page.getByRole('list', { name: 'Conversations' }).getByRole('link').first()
    await firstChat.click()
    await shot('chat-open')
    for (const [path, name] of [['/status', 'status'], ['/calls', 'calls'], ['/contacts', 'contacts'], ['/profile', 'profile'], ['/settings', 'settings']]) {
      await page.goto(`${BASE}${path}`)
      await shot(name)
    }
    await context.close()
  }
}
await browser.close()
console.log('screenshots in', out)
