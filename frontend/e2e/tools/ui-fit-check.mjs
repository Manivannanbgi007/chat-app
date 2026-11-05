// Checks that every main screen fills the browser window in every size: no sideways scrolling, no scrolling of the
// whole page (the page area scrolls inside), and the app frame is exactly as big as the window.
//   node e2e/tools/ui-fit-check.mjs        (stack running, infra/DEMO_ACCOUNTS.md present)

import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5280'
const accounts = readFileSync('../infra/DEMO_ACCOUNTS.md', 'utf-8')
const password = /`(Demo-[^`]+)`/.exec(accounts)[1]
const email = /\| Alice Demo \| (\S+@example\.com)/.exec(accounts)[1]

const SIZES = { phone: [390, 844], 'small phone': [320, 568], tablet: [820, 1180], laptop: [1366, 768], desktop: [1920, 1080], 'ultrawide': [2560, 1080] }
const PAGES = ['/chats', '/status', '/calls', '/contacts', '/profile', '/settings', '/notifications']

const browser = await chromium.launch({ channel: 'chrome' })
let problems = 0
for (const [name, [width, height]] of Object.entries(SIZES)) {
  const context = await browser.newContext({ viewport: { width, height } })
  const page = await context.newPage()
  await page.goto(`${BASE}/login`)
  const loginProblem = await page.evaluate(() => ({ x: document.documentElement.scrollWidth - innerWidth, y: document.documentElement.scrollHeight - innerHeight }))
  if (loginProblem.x > 0 || loginProblem.y > 0) { problems++; console.log(`PROBLEM ${name} /login overflows by`, loginProblem) }
  await page.getByLabel('Email or phone number').fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in' }).click()
  await page.waitForURL(/\/chats/)
  for (const path of PAGES) {
    await page.goto(`${BASE}${path}`)
    await page.waitForTimeout(500)
    const result = await page.evaluate(() => {
      const root = document.getElementById('root').firstElementChild
      const box = root.getBoundingClientRect()
      return {
        sideways: document.documentElement.scrollWidth - innerWidth,
        pageScroll: document.documentElement.scrollHeight - innerHeight,
        frameW: Math.round(box.width) - innerWidth,
        frameH: Math.round(box.height) - innerHeight,
      }
    })
    const ok = result.sideways <= 0 && result.pageScroll <= 0 && Math.abs(result.frameW) <= 1 && Math.abs(result.frameH) <= 1
    if (!ok) problems++
    console.log(`${ok ? 'ok     ' : 'PROBLEM'} ${name.padEnd(11)} ${path.padEnd(15)} ${JSON.stringify(result)}`)
  }
  await context.close()
}
await browser.close()
console.log(problems === 0 ? 'ALL SCREENS FIT THE WINDOW' : `${problems} problem(s)`)
process.exit(problems === 0 ? 0 : 1)
