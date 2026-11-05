import { expect, test, type Page } from '@playwright/test'
import { makeFakePicture, makePdf, makePng } from './fixtures'
import { apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// Files in real browsers: Alice sends a photo with a caption, a PDF and a voice note to Bob, who sees them live
// and can download the document. A fake picture (a program renamed .png) and a file that is too big are refused.
//
// Needs the whole stack running (see README "End-to-end tests"), including media-service and the object storage.
// The microphone is a fake one (Chrome flags in playwright.config.ts), so recording works without hardware.

// Attaches a file like a user: open the paperclip menu, choose the kind, and pick the file in the file dialog.
async function attach(page: Page, kind: 'Photo' | 'Video' | 'Document' | 'Audio', file: { name: string; mimeType: string; buffer: Buffer }) {
  await page.getByRole('button', { name: 'Attach a file' }).click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('menuitem', { name: kind, exact: true }).click()
  await (await chooser).setFiles(file)
}

test('files: photo with caption, PDF download, voice note, and refused files', async ({ browser, request }) => {
  const suffix = Date.now().toString(36)
  const alice = newUser('ma', suffix)
  const bob = newUser('mb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)

  // Alice starts the conversation through the API (the profile of Bob is created from a Kafka event: wait for it)
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
  await bobPage.getByRole('link', { name: new RegExp(alice.displayName) }).click()
  await expect(bobPage.getByRole('heading', { name: alice.displayName })).toBeVisible()
  await expect(alicePage.getByRole('heading', { name: bob.displayName })).toBeVisible()

  const caption = `Sunset ${suffix}`
  const pdf = makePdf()
  const uploads: string[] = []
  alicePage.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/api/media/upload')) uploads.push(r.url())
  })

  await test.step('Alice sends a photo with a caption; Bob sees the picture appear without reloading', async () => {
    await attach(alicePage, 'Photo', { name: 'sunset.png', mimeType: 'image/png', buffer: makePng(64, 48) })
    await expect(alicePage.getByTestId('attachment-preview')).toContainText('sunset.png')
    await alicePage.getByRole('textbox', { name: 'Message' }).fill(caption)
    await alicePage.getByRole('textbox', { name: 'Message' }).press('Enter')

    const bobBubble = bobPage.getByTestId('message').filter({ hasText: caption })
    await expect(bobBubble).toBeVisible()
    const picture = bobBubble.getByAltText('sunset.png')
    await expect(picture).toBeVisible()
    // the picture really loaded from the object storage (a broken link would stay at width 0)
    await expect.poll(() => picture.evaluate((img: HTMLImageElement) => img.naturalWidth), { timeout: 15_000 }).toBeGreaterThan(0)

    // Alice sees her own message with the picture and the sent tick
    const aliceBubble = alicePage.getByTestId('message').filter({ hasText: caption })
    await expect(aliceBubble.getByAltText('sunset.png')).toBeVisible()
    await expect(aliceBubble.getByLabel(/^(Sent|Delivered|Read)$/)).toBeVisible()
  })

  await test.step('Bob opens the picture in the lightbox and closes it with Escape', async () => {
    await bobPage.getByRole('button', { name: 'Open photo sunset.png' }).click()
    await expect(bobPage.getByRole('dialog', { name: /Photo: sunset\.png/ })).toBeVisible()
    await bobPage.keyboard.press('Escape')
    await expect(bobPage.getByRole('dialog')).toHaveCount(0)
  })

  await test.step('Alice sends a PDF; Bob sees the document card and can download it', async () => {
    await attach(alicePage, 'Document', { name: 'e2e-report.pdf', mimeType: 'application/pdf', buffer: pdf })
    await alicePage.getByRole('button', { name: 'Send message' }).click()

    const card = bobPage.getByTestId('message').filter({ hasText: 'e2e-report.pdf' })
    await expect(card).toBeVisible()
    await expect(card).toContainText(/\d+(\.\d)? (B|KB)/) // the size

    // Download: a fresh signed link is requested when the button is pressed
    const linkResponse = bobPage.waitForResponse((r) => r.url().includes('/attachment/url') && r.status() === 200)
    const downloadEvent = bobPage.waitForEvent('download')
    await card.getByRole('button', { name: 'Download e2e-report.pdf' }).click()

    const link = ((await (await linkResponse).json()) as { data: { url: string } }).data.url
    const stored = await request.get(link)
    expect(stored.status()).toBe(200)
    expect(stored.headers()['content-disposition'] ?? '').toContain('attachment')
    expect((await stored.body()).subarray(0, 5).toString('ascii')).toBe('%PDF-')

    const download = await downloadEvent
    expect(download.suggestedFilename()).toBe('e2e-report.pdf')
  })

  await test.step('Alice records a voice note (fake microphone); Bob sees a voice player', async () => {
    await alicePage.getByRole('button', { name: 'Record a voice note' }).click()
    await expect(alicePage.getByRole('group', { name: 'Recording a voice note' })).toBeVisible()
    await alicePage.waitForTimeout(2200)
    await alicePage.getByRole('button', { name: 'Send voice note' }).click()

    // While the signed link is being fetched the bubble shows a placeholder with the same label, so wait for
    // the real <audio> element itself (it appears once the link is there).
    await expect(bobPage.getByTestId('message').locator('audio[aria-label="Voice message"]')).toHaveCount(1, { timeout: 15_000 })
    await expect(bobPage.getByTestId('message').locator('audio[aria-label="Voice message"]')).toHaveAttribute('src', /^http/)
    await expect(alicePage.getByTestId('message').getByLabel('Voice message').first()).toBeVisible()
  })

  await test.step('a program renamed .png is refused: an error message, no bubble stays', async () => {
    const before = await alicePage.getByTestId('message').count()
    await attach(alicePage, 'Photo', { name: 'evil.png', mimeType: 'image/png', buffer: makeFakePicture() })
    await alicePage.getByRole('button', { name: 'Send message' }).click()

    await expect(alicePage.getByRole('alert').filter({ hasText: /rejected|not allowed|not what its name says|could not be read/ })).toBeVisible()
    await expect(alicePage.getByAltText('evil.png')).toHaveCount(0)
    await expect(alicePage.getByTestId('message')).toHaveCount(before)
    // Bob never got it
    await expect(bobPage.getByAltText('evil.png')).toHaveCount(0)
  })

  await test.step('a file over the size limit is refused before any upload starts', async () => {
    const uploadsBefore = uploads.length
    await attach(alicePage, 'Photo', { name: 'huge.png', mimeType: 'image/png', buffer: Buffer.alloc(11 * 1024 * 1024) })

    await expect(alicePage.getByRole('alert').filter({ hasText: /too large.*10 MB/ })).toBeVisible()
    await expect(alicePage.getByTestId('attachment-preview')).toHaveCount(0)
    expect(uploads.length).toBe(uploadsBefore)
  })

  await alicePage.context().close()
  await bobPage.context().close()
})
