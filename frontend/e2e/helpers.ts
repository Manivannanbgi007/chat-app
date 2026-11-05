import { expect, type APIRequestContext, type APIResponse, type Browser, type Page } from '@playwright/test'

// Shared helpers for the end-to-end tests (chat.spec.ts, groups.spec.ts).
// They need the whole stack running (Docker Compose). Emails are read from MailHog.

export const MAILHOG = process.env.E2E_MAILHOG_URL ?? 'http://localhost:8025'
export const PASSWORD = 'Passw0rd123'

export interface TestUser {
  username: string
  displayName: string
  email: string
}

export function newUser(prefix: string, suffix: string): TestUser {
  return {
    username: `e2e_${prefix}_${suffix}`,
    displayName: `${prefix.toUpperCase()} Tester ${suffix}`,
    email: `e2e_${prefix}_${suffix}@example.com`,
  }
}

// The gateway limits /api/auth/** per IP (refill 2/s, burst 10). A test that registers several people can hit
// that limit, so auth calls wait and try again on 429.
async function postWithRetry(
  request: APIRequestContext,
  path: string,
  data: unknown,
  headers: Record<string, string> = {},
): Promise<APIResponse> {
  for (let attempt = 0; ; attempt++) {
    const response = await request.post(path, { data, headers })
    if (response.status() !== 429 || attempt >= 10) return response
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
}

// Reads the 6-digit code that the auth service emailed (it arrives within a second or two).
export async function readVerificationCode(request: APIRequestContext, email: string): Promise<string> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await request.get(`${MAILHOG}/api/v2/search`, { params: { kind: 'to', query: email } })
    const body = (await response.json()) as { items: { Content: { Body: string } }[] }
    for (const item of body.items) {
      const match = /verification code is: (\d{6})/.exec(item.Content.Body)
      if (match) return match[1]
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No verification email for ${email}`)
}

// Registers through the REST API (the UI for it is covered by unit tests), verifies the email and
// returns the new user's id.
export async function registerAndVerify(request: APIRequestContext, user: TestUser): Promise<string> {
  const register = await postWithRetry(request, '/api/auth/register', {
    username: user.username,
    email: user.email,
    password: PASSWORD,
    displayName: user.displayName,
  })
  expect(register.status(), await register.text()).toBe(201)
  const userId = ((await register.json()) as { data: { userId: string } }).data.userId

  const otp = await readVerificationCode(request, user.email)
  const verify = await postWithRetry(request, '/api/auth/verify-otp', {
    identifier: user.email,
    otp,
    purpose: 'EMAIL_VERIFY',
  })
  expect(verify.status(), await verify.text()).toBe(200)
  return userId
}

// Logs in through the REST API and returns the access token (for setting things up or checking results).
export async function apiLogin(request: APIRequestContext, user: TestUser): Promise<string> {
  const response = await postWithRetry(request, '/api/auth/login', { identifier: user.email, password: PASSWORD })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()) as { data: { accessToken: string } }).data.accessToken
}

export function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` }
}

// Adds a contact through the API. The other person's profile is created asynchronously (Kafka),
// so the first attempts can answer 404: try again for a while.
export async function addContactViaApi(request: APIRequestContext, token: string, contactUserId: string): Promise<void> {
  await expect(async () => {
    const response = await request.post('/api/contacts', { data: { contactUserId }, headers: bearer(token) })
    expect([201, 409], await response.text()).toContain(response.status())
  }).toPass({ timeout: 30_000 })
}

// A new browser window for one person, logged in through the real login page.
export async function openLoggedInPage(browser: Browser, user: TestUser): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/login')
  await page.getByLabel('Email or phone number').fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await page.getByLabel('Remember me').check()
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page).toHaveURL(/\/chats/)
  return page
}
