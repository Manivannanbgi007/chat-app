import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { addContactViaApi, apiLogin, bearer, newUser, openLoggedInPage, registerAndVerify } from './helpers'

// "Connected" is not the same as "audio and video really arrive". This spec reads the browsers' own WebRTC
// statistics during a call: bytes received per media kind, and which network path was chosen
// (direct "host" path, or the Coturn relay when only the relay is allowed).
//
// Needs the whole stack running, including call-signaling-service and Coturn (see README, "End-to-end tests").

interface PathStats {
  audioBytes: number
  videoBytes: number
  localType: string
  remoteType: string
}

// Keeps every RTCPeerConnection the app creates in window.__pcs, so the test can ask it for statistics.
async function watchPeerConnections(context: BrowserContext) {
  await context.addInitScript(() => {
    const Original = window.RTCPeerConnection
    const created: RTCPeerConnection[] = []
    ;(window as unknown as { __pcs: RTCPeerConnection[] }).__pcs = created
    const Wrapped = function (this: unknown, ...args: ConstructorParameters<typeof RTCPeerConnection>) {
      const pc = new Original(...args)
      created.push(pc)
      return pc
    } as unknown as typeof RTCPeerConnection
    Wrapped.prototype = Original.prototype
    window.RTCPeerConnection = Wrapped
  })
}

async function readStats(page: Page): Promise<PathStats> {
  return page.evaluate(async () => {
    const pcs = (window as unknown as { __pcs: RTCPeerConnection[] }).__pcs
    const report = await pcs[pcs.length - 1].getStats()
    const stats: PathStats = { audioBytes: 0, videoBytes: 0, localType: '', remoteType: '' }
    report.forEach((item) => {
      if (item.type === 'inbound-rtp') {
        if (item.kind === 'audio') stats.audioBytes += item.bytesReceived ?? 0
        if (item.kind === 'video') stats.videoBytes += item.bytesReceived ?? 0
      }
      if (item.type === 'candidate-pair' && item.nominated && item.state === 'succeeded') {
        stats.localType = report.get(item.localCandidateId)?.candidateType ?? ''
        stats.remoteType = report.get(item.remoteCandidateId)?.candidateType ?? ''
      }
    })
    return stats
  })
}

async function startCall(caller: Page, callee: Page, button: 'Video call' | 'Voice call', conversationId: string) {
  await caller.goto(`/chats/${conversationId}`)
  await caller.getByRole('button', { name: button }).click()
  await expect(callee.getByTestId('call-overlay')).toHaveAttribute('data-call-state', 'incoming', { timeout: 20_000 })
  await callee.getByRole('button', { name: 'Accept' }).click()
  for (const page of [caller, callee]) {
    await expect(page.getByTestId('call-overlay')).toHaveAttribute('data-connection-state', 'connected', { timeout: 30_000 })
  }
}

async function hangUp(caller: Page, callee: Page) {
  await caller.getByRole('button', { name: 'Hang up' }).click()
  for (const page of [caller, callee]) {
    await expect(page.getByTestId('call-overlay')).toHaveAttribute('data-call-state', 'idle', { timeout: 15_000 })
  }
}

// See calls.spec.ts: Chrome's fake camera / microphone sometimes stall; a failed run is tried again (up to 2 times).
test.describe.configure({ retries: 2 })

test('calls: audio and video bytes really arrive, directly and through the Coturn relay', async ({ browser, request }) => {
  test.setTimeout(240_000)
  const suffix = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
  const alice = newUser('flowa', suffix)
  const bob = newUser('flowb', suffix)
  await registerAndVerify(request, alice)
  const bobId = await registerAndVerify(request, bob)

  const aliceToken = await apiLogin(request, alice)
  await addContactViaApi(request, aliceToken, bobId)
  const created = await request.post('/api/conversations', { data: { type: 'PRIVATE', userId: bobId }, headers: bearer(aliceToken) })
  const conversationId = ((await created.json()) as { data: { id: string } }).data.id

  const alicePage = await openLoggedInPage(browser, alice)
  const bobPage = await openLoggedInPage(browser, bob)
  for (const page of [alicePage, bobPage]) {
    await page.context().grantPermissions(['camera', 'microphone'])
    await watchPeerConnections(page.context())
  }
  await bobPage.goto(`/chats/${conversationId}`)   // the init script applies to documents loaded from now on

  await test.step('direct video call: audio AND video bytes arrive on both sides over a direct path', async () => {
    await startCall(alicePage, bobPage, 'Video call', conversationId)
    for (const page of [alicePage, bobPage]) {
      // Media does not start at the same moment on a busy computer (two browsers, 25 containers): wait for the numbers
      // to arrive (up to 25 s) instead of reading them once after a fixed pause.
      await expect.poll(async () => (await readStats(page)).audioBytes, { message: 'audio bytes received', timeout: 25_000 }).toBeGreaterThan(1000)
      await expect.poll(async () => (await readStats(page)).videoBytes, { message: 'video bytes received', timeout: 25_000 }).toBeGreaterThan(10_000)
      const stats = await readStats(page)
      expect(['host', 'srflx', 'prflx'], `local path ${stats.localType}`).toContain(stats.localType)
      expect(stats.localType, 'direct call must not use the relay').not.toBe('relay')
    }
    await hangUp(alicePage, bobPage)
  })

  await test.step('relay-only voice call: the chosen path is the Coturn relay and audio bytes still arrive', async () => {
    for (const page of [alicePage, bobPage]) {
      await page.evaluate(() => localStorage.setItem('chatapp.iceTransportPolicy', 'relay'))
    }
    await startCall(bobPage, alicePage, 'Voice call', conversationId)
    for (const page of [alicePage, bobPage]) {
      await expect.poll(async () => (await readStats(page)).audioBytes, { message: 'audio bytes received through the relay', timeout: 25_000 }).toBeGreaterThan(1000)
      await expect.poll(async () => (await readStats(page)).localType, { message: 'with relay-only the local side must be a relay candidate', timeout: 25_000 }).toBe('relay')
    }
    await hangUp(bobPage, alicePage)
    for (const page of [alicePage, bobPage]) {
      await page.evaluate(() => localStorage.removeItem('chatapp.iceTransportPolicy'))
    }
  })
})
