// Screenshots of the parts that need a click: dialogs, menus, panels, the story viewer and the call screen.
//   node e2e/tools/ui-interactions.mjs [label] [light|dark]
// Same requirements as ui-screenshots.mjs (stack running, infra/DEMO_ACCOUNTS.md).

import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const label = process.argv[2] ?? 'interactions'
const theme = process.argv[3] ?? 'light'
const out = join('ui-screenshots', label)
mkdirSync(out, { recursive: true })

const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const emails = Object.fromEntries([...accounts.matchAll(/\| (\w+) Demo \| (\S+@example\.com)/g)].map((m) => [m[1].toLowerCase(), m[2]]))

const browser = await chromium.launch({ channel: 'chrome', args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] })
const make = async (who) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme, permissions: ['camera', 'microphone'] })
  await context.addInitScript((t) => localStorage.setItem('chat-app-theme', t), theme)
  const page = await context.newPage()
  await page.goto(`${BASE}/login`)
  await page.getByLabel('Email or phone number').fill(emails[who])
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in' }).click()
  await page.waitForURL(/\/chats/)
  return page
}
const alice = await make('alice')
const bob = await make('bob')
const shot = async (page, name) => {
  await page.waitForTimeout(700)
  await page.screenshot({ path: join(out, `${theme}-${name}.png`) })
}

await alice.getByRole('list', { name: 'Conversations' }).getByRole('link').first().click()
await shot(alice, '1-chat')

// notification bell panel
await alice.getByRole('button', { name: /^Notifications/ }).click()
await shot(alice, '2-notifications-panel')
await alice.keyboard.press('Escape')

// attach menu
await alice.getByRole('button', { name: 'Attach a file' }).click()
await shot(alice, '3-attach-menu')
await alice.keyboard.press('Escape')

// chat options menu
await alice.getByRole('button', { name: /options|menu/i }).first().click().catch(() => {})
await shot(alice, '4-chat-menu')
await alice.keyboard.press('Escape')

// new group dialog
await alice.getByRole('button', { name: 'New group' }).click()
await shot(alice, '5-new-group')
await alice.getByRole('dialog').getByRole('button', { name: /close|cancel/i }).first().click()

// typing indicator on alice's side while bob types
await bob.getByRole('list', { name: 'Conversations' }).getByRole('link').first().click()
await bob.getByRole('textbox', { name: 'Message' }).pressSequentially('typing something', { delay: 30 })
await alice.waitForTimeout(400)
await shot(alice, '6-typing')

// incoming call on bob's side
await alice.getByRole('button', { name: 'Video call' }).click()
await bob.getByTestId('call-overlay').waitFor({ state: 'visible' })
await shot(bob, '7-incoming-call')
await bob.getByRole('button', { name: 'Accept' }).click()
await alice.getByTestId('call-overlay').and(alice.locator('[data-connection-state="connected"]')).waitFor({ timeout: 30000 })
await shot(alice, '8-active-video-call')
await alice.getByRole('button', { name: 'Hang up' }).click()

// story viewer
await alice.goto(`${BASE}/status`)
await alice.getByRole('button', { name: 'View my status' }).click()
await shot(alice, '9-story-viewer')
await alice.keyboard.press('Escape')
await alice.getByRole('button', { name: 'Add text status' }).click()
await shot(alice, '10-text-composer')

await browser.close()
console.log('screenshots in', out)
