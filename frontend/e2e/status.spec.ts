import { expect, test } from '@playwright/test'
import { makePng } from './fixtures'
import { addContactViaApi, apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// Status / stories in real browsers: Alice posts a text status and a photo status, Bob (who has Alice as a
// contact) sees them in "Recent updates", watches them, and Alice sees Bob in the viewer list. Then Alice limits
// her audience (Bob no longer sees her) and deletes a status.
//
// Needs the whole stack running (see README "End-to-end tests"), including status-service.

test('status: post text and photo, watch, viewers, privacy and delete', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('sa', suffix)
  const bob = newUser('sb', suffix)
  const aliceId = await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)

  // Audience = the people who have Alice in THEIR contact list, so Bob adds Alice (Alice adds Bob too, as in real life).
  const bobToken = await apiLogin(request, bob)
  const aliceToken = await apiLogin(request, alice)
  await addContactViaApi(request, bobToken, aliceId)
  await addContactViaApi(request, aliceToken, bobId)

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  await alicePage.goto('/status')
  await bobPage.goto('/status')
  await expect(bobPage.getByText(/No status updates from your contacts yet/)).toBeVisible()

  const text = `Hello from Alice ${suffix}`
  await test.step('Alice posts a text status', async () => {
    await alicePage.getByRole('button', { name: 'Add text status' }).click()
    await alicePage.getByRole('textbox', { name: 'Status text' }).fill(text)
    await alicePage.getByRole('button', { name: 'Post' }).click()
    await expect(alicePage.getByTestId('text-status-composer')).toBeHidden()
    await expect(alicePage.getByTestId('my-status')).toContainText('1 update')
  })

  const caption = `Sunset ${suffix}`
  await test.step('Alice posts a photo status with a caption', async () => {
    await alicePage.getByLabel('Photo or video file').setInputFiles({ name: 'sunset.png', mimeType: 'image/png', buffer: makePng(64, 48) })
    await alicePage.getByRole('textbox', { name: 'Caption' }).fill(caption)
    await alicePage.getByRole('button', { name: 'Post' }).click()
    await expect(alicePage.getByTestId('media-status-composer')).toBeHidden({ timeout: 20_000 })
    await expect(alicePage.getByTestId('my-status')).toContainText('2 updates')
  })

  await test.step('Bob sees Alice under "Recent updates" and watches both statuses', async () => {
    await bobPage.reload()   // the page also polls every 30 s; a reload is faster
    const row = bobPage.getByTestId('recent-updates').getByTestId('status-group')
    await expect(row).toHaveAccessibleName(alice.displayName)
    await row.click()

    const viewer = bobPage.getByRole('dialog', { name: `Status of ${alice.displayName}` })
    await expect(viewer.getByTestId('story-text')).toContainText(text)
    await viewer.getByRole('button', { name: 'Next' }).click()
    const photo = viewer.getByTestId('story-photo')
    await expect(photo).toBeVisible()
    await expect.poll(() => photo.evaluate((img: HTMLImageElement) => img.naturalWidth), { timeout: 15_000 }).toBeGreaterThan(0)
    await expect(viewer).toContainText(caption)
    await viewer.getByRole('button', { name: 'Close' }).click()
    await expect(viewer).toBeHidden()

    // everything was seen: Alice moves to "Viewed updates"
    await bobPage.reload()
    await expect(bobPage.getByTestId('viewed-updates').getByTestId('status-group')).toHaveAccessibleName(alice.displayName)
    await expect(bobPage.getByTestId('recent-updates')).toHaveCount(0)
  })

  await test.step('Alice sees Bob in the viewer list', async () => {
    await alicePage.reload()
    await expect(alicePage.getByTestId('my-view-count')).toHaveText('1 view')
    await alicePage.getByRole('button', { name: 'View my status' }).click()
    await alicePage.getByRole('dialog', { name: 'Status of My status' }).getByRole('button', { name: 'Viewers' }).click()
    const viewers = alicePage.getByRole('dialog', { name: 'Viewers' })
    await expect(viewers.getByRole('list', { name: 'People who viewed this status' })).toContainText(bob.displayName)
    await viewers.getByRole('button', { name: 'Close viewers' }).click()
  })

  await test.step('Alice shares only with nobody: Bob no longer sees her status (applies to statuses already posted)', async () => {
    const response = await request.put('/api/statuses/privacy', {
      data: { mode: 'ONLY_SHARE_WITH', userIds: [] },
      headers: bearer(aliceToken),
    })
    expect(response.status(), await response.text()).toBe(200)
    await bobPage.reload()
    await expect(bobPage.getByText(/No status updates from your contacts yet/)).toBeVisible()

    const back = await request.put('/api/statuses/privacy', { data: { mode: 'CONTACTS', userIds: [] }, headers: bearer(aliceToken) })
    expect(back.status(), await back.text()).toBe(200)
    await bobPage.reload()
    await expect(bobPage.getByTestId('status-group')).toHaveAccessibleName(alice.displayName)
  })

  await test.step('Alice deletes her statuses; they disappear for Bob too', async () => {
    await alicePage.reload()
    await alicePage.getByRole('button', { name: 'View my status' }).click()
    const viewer = alicePage.getByRole('dialog', { name: 'Status of My status' })
    for (let i = 0; i < 2; i++) {
      await viewer.getByRole('button', { name: 'Delete status' }).click()
      await alicePage.getByRole('button', { name: 'Delete', exact: true }).click()
      if (i === 0) await expect(viewer).toBeVisible()
    }
    await expect(alicePage.getByTestId('story-viewer')).toBeHidden()
    await expect(alicePage.getByTestId('my-status')).toContainText('Add a status update')

    await bobPage.reload()
    await expect(bobPage.getByText(/No status updates from your contacts yet/)).toBeVisible()
  })
})
