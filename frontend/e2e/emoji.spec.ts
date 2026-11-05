import { expect, test } from '@playwright/test'
import { apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// Emoji in real browsers: Alice picks emoji from the panel (search, tabs, recently used), puts one in the MIDDLE of a
// sentence, sends; Bob sees exactly the same characters live and after a reload. A message that is only emoji is big.

test('emoji: pick, insert at the cursor, send, receive, big emoji-only messages', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('ea', suffix)
  const bob = newUser('eb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)

  const aliceToken = await apiLogin(request, alice)
  let conversationId = ''
  await expect(async () => {
    const response = await request.post('/api/conversations', { data: { type: 'PRIVATE', userId: bobId }, headers: bearer(aliceToken) })
    expect([200, 201], await response.text()).toContain(response.status())
    conversationId = ((await response.json()) as { data: { id: string } }).data.id
  }).toPass({ timeout: 30_000 })

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  await alicePage.goto(`/chats/${conversationId}`)
  await bobPage.goto(`/chats/${conversationId}`)
  const box = alicePage.getByRole('textbox', { name: 'Message' })
  const panel = alicePage.getByRole('dialog', { name: 'Emoji' })

  await test.step('the panel opens with search, tabs and a grid; a search finds an emoji', async () => {
    await alicePage.getByRole('button', { name: 'Add emoji' }).click()
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('tab')).toHaveCount(8) // 8 groups (no "recent" tab before the first pick)
    await expect(panel).toBeInViewport({ ratio: 1 })
    await panel.getByRole('searchbox', { name: 'Search emoji' }).fill('thumbs up')
    await panel.getByRole('button', { name: /^thumbs up/ }).click()
    await expect(box).toHaveValue('👍')
  })

  await test.step('an emoji goes into the middle of a sentence, where the cursor is', async () => {
    await panel.getByRole('searchbox', { name: 'Search emoji' }).fill('')
    await box.fill('Great job team')
    await box.evaluate((element: HTMLTextAreaElement) => element.setSelectionRange(5, 5)) // after "Great"
    await panel.getByRole('searchbox', { name: 'Search emoji' }).fill('party popper')
    await panel.getByRole('button', { name: /^party popper/ }).click()
    await expect(box).toHaveValue('Great🎉 job team')
    await alicePage.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
    await expect(box).toBeFocused()
  })

  await test.step('it is sent as it is, and Bob sees the same characters live', async () => {
    await box.press('Enter')
    await expect(bobPage.getByTestId('message').filter({ hasText: 'Great🎉 job team' })).toBeVisible()
    await expect(alicePage.getByTestId('message').filter({ hasText: 'Great🎉 job team' })).toBeVisible()
  })

  await test.step('typing an emoji with the keyboard (or pasting) works too', async () => {
    await box.fill('Thanks 🙏😊')
    await box.press('Enter')
    await expect(bobPage.getByTestId('message').filter({ hasText: 'Thanks 🙏😊' })).toBeVisible()
  })

  await test.step('a message of only one emoji is shown BIG, on both sides', async () => {
    await alicePage.getByRole('button', { name: 'Add emoji' }).click()
    // "recently used" is the first tab now, with the emoji picked before
    await expect(panel.getByRole('tab', { name: 'Recently used', selected: true })).toBeVisible()
    await panel.getByRole('searchbox', { name: 'Search emoji' }).fill('red heart')
    await panel.getByRole('button', { name: /^red heart/ }).click()
    await alicePage.keyboard.press('Escape')
    await box.press('Enter')

    const bigOnBob = bobPage.getByTestId('message').filter({ hasText: '❤️' }).getByText('❤️')
    await expect(bigOnBob).toBeVisible()
    const size = await bigOnBob.evaluate((element) => parseFloat(getComputedStyle(element).fontSize))
    expect(size, 'font size of a single emoji in px').toBeGreaterThan(40)
    const normal = await bobPage.getByText('Thanks 🙏😊').evaluate((element) => parseFloat(getComputedStyle(element).fontSize))
    expect(size).toBeGreaterThan(normal * 2)
  })

  await test.step('everything is still there after a reload (stored as real emoji)', async () => {
    await bobPage.reload()
    for (const text of ['Great🎉 job team', 'Thanks 🙏😊', '❤️']) {
      await expect(bobPage.getByTestId('message').filter({ hasText: text })).toBeVisible()
    }
  })
})
