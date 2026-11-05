// Notification panel check: creates notifications for Alice (while she is offline: messages; and a group invitation),
// then opens the bell, takes screenshots in both switch states and clicks an item.
//   node e2e/tools/ui-notifications.mjs [label] [light|dark]
// Needs the stack running and infra/DEMO_ACCOUNTS.md. Changes Alice's notification switches and puts them back.
import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const GATEWAY = process.env.GATEWAY ?? 'http://localhost:8200'
const label = process.argv[2] ?? 'notifications'
const theme = process.argv[3] ?? 'light'
const out = join('ui-screenshots', label)
mkdirSync(out, { recursive: true })

const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const emails = Object.fromEntries([...accounts.matchAll(/\| (\w+) Demo \| (\S+@example\.com)/g)].map((m) => [m[1].toLowerCase(), m[2]]))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function api(method, path, body, token) {
  await sleep(300)
  const response = await fetch(GATEWAY + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  return text ? JSON.parse(text) : null
}
const loginToken = async (who) => (await api('POST', '/api/auth/login', { identifier: emails[who], password }))?.data?.accessToken
const me = async (token) => (await api('GET', '/api/users/me', null, token)).data

const bobToken = await loginToken('bob')
const aliceToken = await loginToken('alice')
const aliceId = (await me(aliceToken)).id
const carolId = (await me(await loginToken('carol'))).id
const original = (await api('GET', '/api/notifications/preferences', null, aliceToken)).data
const setPrefs = (prefs) => api('PUT', '/api/notifications/preferences', prefs, aliceToken)
await setPrefs({ pushEnabled: true, messagesEnabled: true, groupsEnabled: true, callsEnabled: true })

// Alice is offline now (no browser open): Bob writes to her, and invites her into a group
const conv = (await api('POST', '/api/conversations', { type: 'PRIVATE', userId: aliceId }, bobToken)).data.id
for (const text of ['Are you coming to the review?', 'It starts in 10 minutes']) {
  await api('POST', '/api/messages', { conversationId: conv, type: 'TEXT', content: text, idempotencyKey: crypto.randomUUID() }, bobToken)
}
await api('POST', '/api/conversations', { type: 'GROUP', name: `Project Phoenix ${Date.now().toString(36).slice(-3)}`, memberIds: [aliceId, carolId] }, bobToken)
await sleep(1500)

const browser = await chromium.launch({ channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 1366, height: 768 }, colorScheme: theme })
await context.addInitScript((t) => localStorage.setItem('chat-app-theme', t), theme)
const page = await context.newPage()
await page.goto(`${BASE}/login`)
await page.getByLabel('Email or phone number').fill(emails.alice)
await page.getByLabel('Password', { exact: true }).fill(password)
await page.getByRole('button', { name: 'Log in' }).click()
await page.waitForURL(/\/chats/)
await page.goto(`${BASE}/status`)
await page.waitForTimeout(800)

const shot = async (name) => {
  await page.waitForTimeout(600)
  await page.screenshot({ path: join(out, `${theme}-${name}.png`) })
}
const bell = page.getByRole('button', { name: /^Notifications/ })

await bell.click()
await shot('1-panel-on')
const rows = await page.getByRole('list', { name: 'Notifications' }).getByRole('button').count()
console.log('notification rows in the panel:', rows)

// click the first one: where do we land?
await page.getByRole('list', { name: 'Notifications' }).getByRole('button').first().click()
await page.waitForTimeout(1200)
console.log('after clicking the first notification the URL is', new URL(page.url()).pathname, '| panel still open:', await page.getByRole('dialog', { name: 'Notifications panel' }).isVisible().catch(() => false))
await shot('2-after-click')

// all switches off
await setPrefs({ pushEnabled: false, messagesEnabled: false, groupsEnabled: false, callsEnabled: false })
await page.goto(`${BASE}/status`)
await page.waitForTimeout(800)
await bell.click()
await shot('3-panel-all-off')
await page.keyboard.press('Escape')

// only messages off
await setPrefs({ pushEnabled: true, messagesEnabled: false, groupsEnabled: true, callsEnabled: true })
await page.goto(`${BASE}/status`)
await page.waitForTimeout(800)
await bell.click()
await shot('4-panel-messages-off')

await setPrefs(original)
await browser.close()
console.log('screenshots in', out)
