// Screenshots of the emoji picker and of emoji messages.   node e2e/tools/ui-emoji.mjs [label]
import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const out = join('ui-screenshots', process.argv[2] ?? 'emoji')
mkdirSync(out, { recursive: true })
const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const email = /\| Mani Demo \| (\S+@example\.com)/.exec(accounts)[1]

const browser = await chromium.launch({ channel: 'chrome' })
for (const theme of ['light', 'dark']) {
  const context = await browser.newContext({ viewport: { width: 1366, height: 768 }, colorScheme: theme })
  await context.addInitScript((t) => localStorage.setItem('chat-app-theme', t), theme)
  const page = await context.newPage()
  await page.goto(`${BASE}/login`)
  await page.getByLabel('Email or phone number').fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in' }).click()
  await page.waitForURL(/\/chats/)
  await page.getByRole('list', { name: 'Conversations' }).getByRole('link').filter({ hasText: 'Ravi' }).first().click()
  const box = page.getByRole('textbox', { name: 'Message' })
  const send = async (text) => { await box.fill(text); await box.press('Enter'); await page.waitForTimeout(300) }
  await send('Shipping today 🚀🎉')
  await send('👍')
  await send('😂😂')

  await page.getByRole('button', { name: 'Add emoji' }).click()
  await page.waitForTimeout(500)
  await page.screenshot({ path: join(out, `${theme}-1-picker.png`) })
  await page.getByRole('dialog', { name: 'Emoji' }).getByRole('tab', { name: 'Hearts & symbols' }).click()
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `${theme}-2-picker-hearts.png`) })
  await page.getByRole('dialog', { name: 'Emoji' }).getByRole('searchbox').fill('cat')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `${theme}-3-picker-search.png`) })
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
  await page.screenshot({ path: join(out, `${theme}-4-messages.png`) })
  await context.close()
}
await browser.close()
console.log('screenshots in', out)
