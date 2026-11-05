import { expect, test, type Page } from '@playwright/test'
import { addContactViaApi, apiLogin, newUser, openLoggedInPage, registerAndVerify, type TestUser } from './helpers'

// Presence ("online" / "last seen") and notifications (bell, unread badge, list) in real browsers.
//
// Needs the whole stack running, including presence-service and notification-service (see README "End-to-end tests").
// Every scenario creates its own new users, so the tests can run in any order.

const bell = (page: Page) => page.getByRole('button', { name: /^Notifications/ })
const badge = (page: Page) => page.getByTestId('bell-badge')

// Alice (already has Bob in her contacts) opens a private chat with him from "My contacts".
async function openChatWith(page: Page, other: TestUser): Promise<void> {
  await page.getByRole('link', { name: 'Contacts', exact: true }).click()
  await page.getByRole('tab', { name: 'My contacts' }).click()
  await page.getByRole('button', { name: 'Start a conversation' }).click()
  await expect(page).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
  await expect(page.getByRole('heading', { name: other.displayName })).toBeVisible()
}

async function send(page: Page, text: string): Promise<void> {
  const box = page.getByRole('textbox', { name: 'Message' })
  await box.fill(text)
  await box.press('Enter')
  await expect(page.getByTestId('message').filter({ hasText: text })).toBeVisible()
}

test('presence: Bob is online for Alice, then logs out and Alice sees "last seen"', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('pa', suffix)
  const bob = newUser('pb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)
  await addContactViaApi(request, await apiLogin(request, alice), bobId)

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob) // logging in sends the first heartbeat

  await test.step('Alice opens the chat and sees Bob online', async () => {
    await openChatWith(alicePage, bob)
    await expect(alicePage.getByTestId('presence')).toContainText('online', { timeout: 25_000 })
  })

  await test.step('Bob logs out: Alice sees "last seen" within 25 seconds (she asks every 10 seconds)', async () => {
    await bobPage.getByRole('button', { name: 'Log out' }).click()
    await expect(bobPage).toHaveURL(/\/login/)

    await expect(alicePage.getByTestId('presence')).toContainText('last seen', { timeout: 25_000 })
    await expect(alicePage.getByTestId('presence')).not.toContainText('online')
  })
})

test('notifications: messages sent while Bob was logged out show as ONE notification, opening the chat clears the bell', async ({
  browser,
  request,
}) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('na', suffix)
  const bob = newUser('nb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)
  await addContactViaApi(request, await apiLogin(request, alice), bobId)

  // Bob never logs in until the end.
  const alicePage = await openLoggedInPage(browser, alice)

  await test.step('Alice sends two messages to Bob', async () => {
    await openChatWith(alicePage, bob)
    await send(alicePage, 'Are you there?')
    await send(alicePage, 'Call me when you can')
  })

  const bobPage = await openLoggedInPage(browser, bob)

  await test.step('Bob logs in: the bell shows 1 and the list has "2 new messages" titled with Alice', async () => {
    await expect(badge(bobPage)).toHaveText('1', { timeout: 20_000 })

    await bell(bobPage).click()
    const entries = bobPage.getByRole('list', { name: 'Notifications' }).getByRole('listitem')
    await expect(entries).toHaveCount(1)
    await expect(entries.first()).toContainText(alice.displayName)
    await expect(entries.first()).toContainText('2 new messages')
  })

  await test.step('Clicking it opens the chat; a few seconds later the bell is back to 0', async () => {
    await bobPage.getByRole('list', { name: 'Notifications' }).getByRole('button', { name: /2 new messages/ }).click()
    await expect(bobPage).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
    await expect(bobPage.getByTestId('message').filter({ hasText: 'Call me when you can' })).toBeVisible()

    // The count is read again 3 and 8 seconds after the chat opened, so allow for that.
    await expect(badge(bobPage)).toHaveCount(0, { timeout: 25_000 })
  })
})

test('notifications: Bob is online, Alice adds him to a group, and his bell goes up live without a reload', async ({
  browser,
  request,
}) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('ca', suffix)
  const bob = newUser('cb', suffix)
  const groupName = `E2E Notify Group ${suffix}`
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)
  await addContactViaApi(request, await apiLogin(request, alice), bobId)

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  await expect(badge(bobPage)).toHaveCount(0)

  await test.step('Alice creates a group with Bob', async () => {
    await alicePage.getByRole('button', { name: 'New group' }).click()
    const dialog = alicePage.getByRole('dialog', { name: 'New group: add members' })
    await dialog.getByRole('checkbox', { name: new RegExp(bob.displayName) }).check()
    await dialog.getByRole('button', { name: 'Next' }).click()
    await alicePage.getByLabel('Group name').fill(groupName)
    await alicePage.getByRole('button', { name: 'Create group' }).click()
    await expect(alicePage).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
  })

  await test.step('Bob\'s bell shows 1 (no reload) and the panel says Alice added him', async () => {
    await expect(badge(bobPage)).toHaveText('1', { timeout: 20_000 })

    await bell(bobPage).click()
    const entries = bobPage.getByRole('list', { name: 'Notifications' }).getByRole('listitem')
    await expect(entries.first()).toContainText(`${alice.displayName} added you`)
  })
})
