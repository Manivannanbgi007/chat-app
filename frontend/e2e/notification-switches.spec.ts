import { expect, test } from '@playwright/test'
import { addContactViaApi, apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// The bell panel and the switches in Settings > Notifications, in a real browser:
//  - the panel is fully visible (not cut off by the menu) and shows ONE state at a time
//  - a click on a notification always goes somewhere (here: the group it is about)
//  - the switches are one truth: Settings, the panel, and after a reload

test('notification panel: one clear state at a time, clickable, and the switches decide', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('swa', suffix)
  const bob = newUser('swb', suffix)
  const aliceId = await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)
  const aliceToken = await apiLogin(request, alice)
  const bobToken = await apiLogin(request, bob)
  await addContactViaApi(request, bobToken, aliceId)
  await addContactViaApi(request, aliceToken, bobId)

  const page = await openLoggedInPage(browser, alice)
  const bell = page.getByRole('button', { name: /^Notifications/ })
  const panel = page.getByRole('dialog', { name: 'Notifications panel' })

  await test.step('a brand-new account: the panel says ONE thing, "all caught up", and is fully on screen', async () => {
    await bell.click()
    await expect(panel.getByText('You are all caught up')).toBeVisible()
    await expect(panel.getByText('Notifications are turned off')).toHaveCount(0)
    await expect(panel.getByText('Nothing new')).toHaveCount(0)
    await expect(panel).toBeInViewport({ ratio: 1 }) // not cut off by the left menu
    await page.keyboard.press('Escape')
    await expect(panel).toHaveCount(0)
  })

  await test.step('Bob adds Alice to a group: ONE entry appears, with one line of text', async () => {
    const created = await request.post('/api/conversations', {
      data: { type: 'GROUP', name: `Switch test ${suffix}`, memberIds: [aliceId] },
      headers: bearer(bobToken),
    })
    expect(created.status(), await created.text()).toBe(201)
    await expect(page.getByTestId('bell-badge')).toHaveText('1', { timeout: 20_000 })
    await bell.click()
    const rows = panel.getByRole('list', { name: 'Notifications' }).getByRole('listitem')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText(`Switch test ${suffix}`)
    await expect(rows.first()).toContainText('added you')
    await expect(panel).toBeInViewport({ ratio: 1 })
  })

  await test.step('clicking it closes the panel and opens that group', async () => {
    await panel.getByRole('list', { name: 'Notifications' }).getByRole('button').first().click()
    await expect(page).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
    await expect(panel).toHaveCount(0)
    await expect(page.getByRole('heading', { name: `Switch test ${suffix}` })).toBeVisible()
    await expect(page.getByTestId('bell-badge')).toHaveCount(0)
  })

  await test.step('Settings: switching every kind off is remembered and the panel says so once', async () => {
    await page.goto('/settings')
    const section = page.locator('#notifications')
    for (const label of ['New messages in private chats', 'Groups', 'Missed calls']) {
      await section.getByText(label, { exact: false }).first().click()
      await expect(section.getByRole('checkbox', { name: new RegExp(`^${label}`) })).not.toBeChecked()
    }
    await page.reload()
    await expect(page.locator('#notifications').getByRole('checkbox', { name: /^Groups/ })).not.toBeChecked() // saved on the server

    await bell.click()
    // the old entry is still listed, with ONE slim note that nothing new will arrive
    await expect(panel.getByText(/Notifications are turned off, so nothing new will arrive/)).toBeVisible()
    await expect(panel.getByText('You are all caught up')).toHaveCount(0)
    await panel.getByRole('button', { name: /Turn on/ }).click() // goes to the settings section
    await expect(page).toHaveURL(/\/settings#notifications/)
    await expect(panel).toHaveCount(0)
  })

  await test.step('switching one kind back on removes the note', async () => {
    await page.locator('#notifications').getByText('Groups', { exact: false }).first().click()
    await expect(page.locator('#notifications').getByRole('checkbox', { name: /^Groups/ })).toBeChecked()
    await bell.click()
    await expect(panel.getByText(/turned off/)).toHaveCount(0)
    await expect(panel).toBeInViewport({ ratio: 1 })
  })
})
