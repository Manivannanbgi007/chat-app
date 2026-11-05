import { expect, test, type Page } from '@playwright/test'
import { newUser, openLoggedInPage, registerAndVerify, type TestUser } from './helpers'

// Two people chat in two separate browsers (two contexts = two independent sessions).
//
// Needs the whole stack running (Docker Compose: gateway, auth, user, conversation, message services,
// MySQL, Redis, Kafka, MailHog). Emails are read from MailHog. Shared helpers live in helpers.ts.

// The profile of the person is created asynchronously from a Kafka event, so search may need a moment.
async function startConversationWith(page: Page, other: TestUser): Promise<void> {
  await page.getByRole('link', { name: 'Contacts', exact: true }).click()
  await page.getByRole('tab', { name: 'Find people' }).click()
  await expect(async () => {
    await page.getByLabel('Search people').fill(other.username)
    await expect(page.getByRole('button', { name: 'Add contact' })).toBeVisible({ timeout: 3000 })
  }).toPass({ timeout: 30_000 })
  await page.getByRole('button', { name: 'Add contact' }).click()
  await expect(page.getByText('In contacts')).toBeVisible()

  await page.getByRole('tab', { name: 'My contacts' }).click()
  await page.getByRole('button', { name: 'Start a conversation' }).click()
  await expect(page).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
}

test('two users chat in real time: delivery, unread badge, read ticks, reply, reload', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('a', suffix)
  const bob = newUser('b', suffix)
  await registerAndVerify(request, alice)
  await registerAndVerify(request, bob)

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)

  await test.step('Alice starts a conversation with Bob', async () => {
    await startConversationWith(alicePage, bob)
    await expect(alicePage.getByRole('heading', { name: bob.displayName })).toBeVisible()
  })

  await test.step('Alice sends a message and sees it as sent', async () => {
    await alicePage.getByRole('textbox', { name: 'Message' }).fill('Hello Bob')
    await alicePage.getByRole('textbox', { name: 'Message' }).press('Enter')
    const bubble = alicePage.getByTestId('message').filter({ hasText: 'Hello Bob' })
    await expect(bubble).toBeVisible()
    await expect(bubble.getByLabel(/^(Sending|Sent|Delivered|Read)$/)).toBeVisible()
  })

  await test.step('Bob sees it appear without reloading, with an unread badge, and it becomes delivered for Alice', async () => {
    const row = bobPage.getByRole('link', { name: new RegExp(alice.displayName) })
    await expect(row).toBeVisible()
    await expect(row).toContainText('Hello Bob')
    await expect(row.getByLabel('1 unread messages')).toBeVisible()

    const aliceBubble = alicePage.getByTestId('message').filter({ hasText: 'Hello Bob' })
    await expect(aliceBubble.getByLabel('Delivered')).toBeVisible()
  })

  await test.step('Bob opens the chat: the badge disappears and Alice sees the blue double tick', async () => {
    await bobPage.getByRole('link', { name: new RegExp(alice.displayName) }).click()
    await expect(bobPage.getByTestId('message').filter({ hasText: 'Hello Bob' })).toBeVisible()
    await expect(bobPage.getByText('1 unread message')).toBeVisible() // the divider shown on opening

    const aliceBubble = alicePage.getByTestId('message').filter({ hasText: 'Hello Bob' })
    await expect(aliceBubble.getByLabel('Read')).toBeVisible()
    await expect(bobPage.getByLabel(/unread messages/)).toHaveCount(0)
  })

  await test.step('Bob types, Alice sees "typing..." (soft check), then Bob replies', async () => {
    await bobPage.getByRole('textbox', { name: 'Message' }).pressSequentially('Hi Alice', { delay: 120 })
    await expect.soft(alicePage.getByText('typing...')).toBeVisible({ timeout: 8000 })
    await bobPage.getByRole('textbox', { name: 'Message' }).press('Enter')

    await expect(alicePage.getByTestId('message').filter({ hasText: 'Hi Alice' })).toBeVisible()
    await expect(bobPage.getByTestId('message').filter({ hasText: 'Hi Alice' })).toBeVisible()
  })

  await test.step('Alice reloads the page and still sees the whole history', async () => {
    await alicePage.reload()
    await expect(alicePage.getByTestId('message').filter({ hasText: 'Hello Bob' })).toBeVisible()
    await expect(alicePage.getByTestId('message').filter({ hasText: 'Hi Alice' })).toBeVisible()
    await expect(alicePage.getByTestId('message')).toHaveCount(2)
  })

  await alicePage.context().close()
  await bobPage.context().close()
})
