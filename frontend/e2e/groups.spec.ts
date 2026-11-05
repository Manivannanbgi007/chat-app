import { expect, test } from '@playwright/test'
import { addContactViaApi, apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// Group chat in real browsers: Alice creates a group with Bob and Carol, Bob follows it live,
// Alice removes Carol (Carol keeps the old messages but not the new ones) and then lets only admins write.
//
// Needs the whole stack running (see README "End-to-end tests"). Carol is only checked through the API.

test('group: create, live messages with sender names, remove a member, only admins can send', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('ga', suffix)
  const bob = newUser('gb', suffix)
  const carol = newUser('gc', suffix)
  const groupName = `E2E Group ${suffix}`

  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)
  const carolId = await registerAndVerify(request, carol)

  // Alice needs Bob and Carol in her contacts to pick them for the group.
  const aliceToken = await apiLogin(request, alice)
  await addContactViaApi(request, aliceToken, bobId)
  await addContactViaApi(request, aliceToken, carolId)
  const carolToken = await apiLogin(request, carol)

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  let groupId = ''

  await test.step('Alice creates the group with Bob and Carol', async () => {
    await alicePage.getByRole('button', { name: 'New group' }).click()
    const dialog = alicePage.getByRole('dialog', { name: 'New group: add members' })
    await dialog.getByRole('checkbox', { name: new RegExp(bob.displayName) }).check()
    await dialog.getByRole('checkbox', { name: new RegExp(carol.displayName) }).check()
    await dialog.getByRole('button', { name: 'Next' }).click()

    await alicePage.getByLabel('Group name').fill(groupName)
    await alicePage.getByRole('button', { name: 'Create group' }).click()

    await expect(alicePage).toHaveURL(/\/chats\/[0-9a-f-]{36}/)
    groupId = /\/chats\/([0-9a-f-]{36})/.exec(alicePage.url())![1]
    await expect(alicePage.getByRole('heading', { name: groupName })).toBeVisible()
  })

  await test.step('Bob sees the new group appear live, with the "created group" note', async () => {
    const row = bobPage.getByRole('link', { name: new RegExp(groupName) })
    await expect(row).toBeVisible()
    await expect(row).toContainText('created group')

    await row.click()
    await expect(bobPage.getByTestId('system-message').filter({ hasText: `${alice.displayName} created group` })).toBeVisible()
  })

  await test.step('Alice writes; Bob gets it live with her name on it', async () => {
    await alicePage.getByRole('textbox', { name: 'Message' }).fill('Hello group')
    await alicePage.getByRole('textbox', { name: 'Message' }).press('Enter')

    const bubble = bobPage.getByTestId('message').filter({ hasText: 'Hello group' })
    await expect(bubble).toBeVisible()
    await expect(bubble).toContainText(alice.displayName)
  })

  await test.step('Alice removes Carol; Bob sees the note', async () => {
    await alicePage.getByRole('button', { name: 'Group info' }).click()
    await alicePage.getByRole('button', { name: `Options for ${carol.displayName}` }).click()
    await alicePage.getByRole('menuitem', { name: 'Remove from group' }).click()
    await alicePage.getByRole('alertdialog').getByRole('button', { name: 'Remove' }).click()

    await expect(bobPage.getByTestId('system-message').filter({ hasText: `removed ${carol.displayName}` })).toBeVisible()
  })

  await test.step('Carol keeps the earlier message but does not get the next one', async () => {
    await alicePage.getByRole('textbox', { name: 'Message' }).fill('After removal')
    await alicePage.getByRole('textbox', { name: 'Message' }).press('Enter')
    await expect(bobPage.getByTestId('message').filter({ hasText: 'After removal' })).toBeVisible()

    const history = await request.get(`/api/conversations/${groupId}/messages`, { headers: bearer(carolToken) })
    expect(history.status(), await history.text()).toBe(200)
    const contents = ((await history.json()) as { data: { items: { content: string }[] } }).data.items.map((m) => m.content)
    expect(contents).toContain('Hello group')
    expect(contents).not.toContain('After removal')

    const conversation = await request.get(`/api/conversations/${groupId}`, { headers: bearer(carolToken) })
    expect(((await conversation.json()) as { data: { membership: { active: boolean } } }).data.membership.active).toBe(false)
  })

  await test.step('Alice lets only admins send; Bob sees the banner instead of the composer', async () => {
    await alicePage.getByRole('switch', { name: 'Only admins can send messages' }).click()
    await expect(alicePage.getByRole('switch', { name: 'Only admins can send messages' })).toHaveAttribute('aria-checked', 'true')

    await expect(bobPage.getByTestId('system-message').filter({ hasText: 'allow only admins to send messages' })).toBeVisible()
    await expect(bobPage.getByText('Only admins can send messages.')).toBeVisible()
    await expect(bobPage.getByRole('textbox', { name: 'Message' })).toHaveCount(0)
  })

  await alicePage.context().close()
  await bobPage.context().close()
})
