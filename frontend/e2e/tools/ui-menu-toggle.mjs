// Screenshots of the left menu closed and open, and a check that the choice is remembered after a reload.
//   node e2e/tools/ui-menu-toggle.mjs [label]
import { chromium } from '@playwright/test'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const out = join('ui-screenshots', process.argv[2] ?? 'menu')
mkdirSync(out, { recursive: true })
const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const email = /\| Alice Demo \| (\S+@example\.com)/.exec(accounts)[1]

const browser = await chromium.launch({ channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 1366, height: 768 } })
const page = await context.newPage()
await page.goto(`${BASE}/login`)
await page.getByLabel('Email or phone number').fill(email)
await page.getByLabel('Password', { exact: true }).fill(password)
await page.getByRole('button', { name: 'Log in' }).click()
await page.waitForURL(/\/chats/)
await page.getByRole('list', { name: 'Conversations' }).getByRole('link').first().click()

const width = () => page.evaluate(() => Math.round(document.querySelector('aside').getBoundingClientRect().width))
const first = await width()
console.log('start: menu width', first, 'toggle label:', await page.getByRole('button', { name: /menu$/ }).getAttribute('aria-label'))

await page.getByRole('button', { name: /^(Collapse|Expand) menu$/ }).click()
await page.waitForTimeout(500)
const second = await width()
await page.screenshot({ path: join(out, '1-after-first-click.png') })
console.log('after click 1: menu width', second)

await page.getByRole('button', { name: /^(Collapse|Expand) menu$/ }).click()
await page.waitForTimeout(500)
const third = await width()
await page.screenshot({ path: join(out, '2-after-second-click.png') })
console.log('after click 2: menu width', third)

// remembered after a reload?
await page.getByRole('button', { name: /^(Collapse|Expand) menu$/ }).click()
await page.waitForTimeout(400)
const before = await width()
await page.reload()
await page.waitForTimeout(800)
const afterReload = await width()
console.log('remembered after reload:', before === afterReload, `(${before} -> ${afterReload})`)

// links still work with names while closed (screen readers)
await page.getByRole('link', { name: 'Contacts', exact: true }).click()
await page.waitForURL(/\/contacts/)
console.log('Contacts link works by its name: yes')
await browser.close()
const ok = first !== second && second !== third && first === third && before === afterReload
console.log(ok ? 'MENU TOGGLE OK' : 'MENU TOGGLE PROBLEM')
process.exit(ok ? 0 : 1)
