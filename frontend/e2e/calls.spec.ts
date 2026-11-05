import { expect, test, type Page } from '@playwright/test'
import { addContactViaApi, apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// One-to-one calls in two real Chrome windows. Chrome gets a fake camera and microphone (playwright.config.ts), so the
// browsers really connect to each other with WebRTC. Needs the whole stack running, including call-signaling-service,
// notification-service and Coturn (the TURN server).
//
// Scenarios: (a) a video call connects, mute, hang up, history; (b) the same with ONLY the TURN server allowed, which
// proves Coturn and its short-lived credentials work; (c) declined; (d) cancelled -> the other person has a missed call.

const overlay = (page: Page) => page.getByTestId('call-overlay')

async function expectCallState(page: Page, state: string, timeout = 20_000) {
  await expect(overlay(page)).toHaveAttribute('data-call-state', state, { timeout })
}

// Both browsers really talk to each other (RTCPeerConnection.connectionState === 'connected').
async function expectConnected(...pages: Page[]) {
  for (const page of pages) {
    await expect(overlay(page)).toHaveAttribute('data-connection-state', 'connected', { timeout: 30_000 })
    await expectCallState(page, 'active')
  }
}

// The "Call ended" screen closes itself after 3 seconds; the next call starts after that.
async function waitUntilIdle(...pages: Page[]) {
  for (const page of pages) await expectCallState(page, 'idle')
}

// Chrome's fake camera / microphone sometimes never finish opening when two browser windows start a call at the same
// moment (the call then never starts: no request reaches the server). That is the test browser, not the app, so a
// failed run is tried again (up to 2 times, with new users); Playwright reports such a run as "flaky".
test.describe.configure({ retries: 2 })

test('calls: video call, TURN-only call, declined call, missed call', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
  const alice = newUser('calla', suffix)
  const bob = newUser('callb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)

  // Calls start from the header of a private chat: create it through the API.
  const aliceToken = await apiLogin(request, alice)
  await addContactViaApi(request, aliceToken, bobId) // also waits until Bob's profile exists
  const created = await request.post('/api/conversations', { data: { type: 'PRIVATE', userId: bobId }, headers: bearer(aliceToken) })
  expect([200, 201], await created.text()).toContain(created.status())
  const conversationId = ((await created.json()) as { data: { id: string } }).data.id

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  for (const page of [alicePage, bobPage]) await page.context().grantPermissions(['camera', 'microphone'])
  await bobPage.goto(`/chats/${conversationId}`)

  await test.step('(a) Alice video-calls Bob from the chat header; Bob accepts; both browsers connect', async () => {
    await alicePage.goto(`/chats/${conversationId}`)
    await alicePage.getByRole('button', { name: 'Video call' }).click()
    await expectCallState(alicePage, 'outgoing')

    await expectCallState(bobPage, 'incoming')
    await expect(bobPage.getByTestId('call-status')).toHaveText('Incoming video call...')
    await expect(bobPage.getByRole('dialog', { name: alice.displayName })).toBeVisible()
    await bobPage.getByRole('button', { name: 'Accept' }).click()

    await expectConnected(alicePage, bobPage)
    await expect(alicePage.getByLabel(`${bob.displayName}'s video`)).toBeVisible()
  })

  await test.step('the timer runs, Alice mutes, Bob hangs up, both see the end', async () => {
    await expect(alicePage.getByTestId('call-timer')).toHaveText(/^0:0[2-9]$|^0:[1-5]\d$/, { timeout: 10_000 })
    await alicePage.getByRole('button', { name: 'Mute' }).click()
    await expect(alicePage.getByRole('button', { name: 'Unmute' })).toHaveAttribute('aria-pressed', 'true')

    await bobPage.getByRole('button', { name: 'Hang up' }).click()
    await expect(bobPage.getByTestId('call-status')).toHaveText('Call ended')
    await expect(alicePage.getByTestId('call-status')).toHaveText('Call ended')
  })

  await test.step("Alice's Calls page shows the answered call with its duration", async () => {
    await waitUntilIdle(alicePage, bobPage)
    await alicePage.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Calls' }).click()
    const row = alicePage.getByTestId('call-row').first()
    await expect(row).toContainText(bob.displayName)
    await expect(row.getByRole('img', { name: 'Outgoing' })).toBeVisible()
    await expect(row.getByTestId('call-outcome')).toHaveText(/^\d+:\d\d$/)
  })

  await test.step('(b) TURN only: with every other route switched off the call still connects (Coturn works)', async () => {
    for (const page of [alicePage, bobPage]) {
      await page.evaluate(() => localStorage.setItem('chatapp.iceTransportPolicy', 'relay'))
    }
    // Bob calls back this time, a voice call, from his chat header.
    await bobPage.getByRole('button', { name: 'Voice call' }).click()
    await expectCallState(alicePage, 'incoming')
    await expect(alicePage.getByTestId('call-status')).toHaveText('Incoming voice call...')
    await alicePage.getByRole('button', { name: 'Accept' }).click()

    await expectConnected(alicePage, bobPage)

    await alicePage.getByRole('button', { name: 'Hang up' }).click()
    await expect(bobPage.getByTestId('call-status')).toHaveText('Call ended')
    for (const page of [alicePage, bobPage]) {
      await page.evaluate(() => localStorage.removeItem('chatapp.iceTransportPolicy'))
    }
    await waitUntilIdle(alicePage, bobPage)
  })

  await test.step('(c) Bob declines: Alice sees "Declined"', async () => {
    await alicePage.goto(`/chats/${conversationId}`)
    await alicePage.getByRole('button', { name: 'Video call' }).click()
    await expectCallState(bobPage, 'incoming')
    await bobPage.getByRole('button', { name: 'Decline' }).click()

    await expect(alicePage.getByTestId('call-status')).toHaveText('Declined')
    await expectCallState(bobPage, 'idle')
    await waitUntilIdle(alicePage)
  })

  await test.step('(d) Alice cancels while it rings: Bob gets a missed-call notification and sees it on his Calls page', async () => {
    await alicePage.getByRole('button', { name: 'Video call' }).click()
    await expectCallState(bobPage, 'incoming')
    await alicePage.getByRole('button', { name: 'Cancel call' }).click()

    await expect(bobPage.getByTestId('call-status')).toHaveText('Missed call')
    await waitUntilIdle(bobPage)

    // notification-service creates it from the CallEndedEvent (Kafka) and pushes it to the bell live.
    const bell = bobPage.getByRole('button', { name: /^Notifications/ })
    await expect(bell).toHaveAccessibleName(/1 unread/, { timeout: 30_000 })
    await bell.click()
    const entry = bobPage.getByRole('list', { name: 'Notifications' }).getByRole('button', { name: /Missed video call/ })
    await expect(entry).toContainText(alice.displayName)
    await entry.click()

    await expect(bobPage).toHaveURL(/\/calls$/)
    const row = bobPage.getByTestId('call-row').first()
    await expect(row).toContainText(alice.displayName)
    await expect(row.getByTestId('call-outcome')).toHaveText('Missed')
    await expect(row.getByRole('img', { name: 'Missed' })).toBeVisible()
  })
})
