import type { MyStatus, Status, StatusGroup } from '../types/status'
import { profile } from './chatFixtures'

// Small builders so status tests only mention the fields they care about.

let counter = 0

// A text status of somebody else that I have not seen yet (override what the test needs).
export function makeStatus(overrides: Partial<Status> = {}): Status {
  counter += 1
  const createdAt = overrides.createdAt ?? new Date(Date.UTC(2026, 10, 4, 10, 0, counter)).toISOString()
  return {
    id: `s-${String(counter).padStart(4, '0')}`,
    ownerId: 'u-ravi',
    type: 'TEXT',
    text: `status ${counter}`,
    backgroundColor: '#0F766E',
    caption: null,
    media: null,
    createdAt,
    expiresAt: new Date(Date.parse(createdAt) + 24 * 3600_000).toISOString(),
    viewedByMe: false,
    ...overrides,
  }
}

export function makeMyStatus(overrides: Partial<MyStatus> = {}): MyStatus {
  return { ...makeStatus({ ownerId: 'u-me', viewedByMe: true }), viewCount: 0, ...overrides }
}

export function makePhotoStatus(overrides: Partial<Status> = {}): Status {
  return makeStatus({
    type: 'IMAGE',
    text: null,
    backgroundColor: null,
    caption: 'At the beach',
    media: { kind: 'IMAGE', mimeType: 'image/png', width: 64, height: 48, durationSeconds: null, hasThumbnail: true },
    ...overrides,
  })
}

export function makeVideoStatus(overrides: Partial<Status> = {}): Status {
  return makeStatus({
    type: 'VIDEO',
    text: null,
    backgroundColor: null,
    media: { kind: 'VIDEO', mimeType: 'video/mp4', width: null, height: null, durationSeconds: 12, hasThumbnail: false },
    ...overrides,
  })
}

// One person's statuses as the feed sends them.
export function makeGroup(ownerId: string, name: string, statuses: Status[]): StatusGroup {
  const owned = statuses.map((status) => ({ ...status, ownerId }))
  return {
    owner: profile(ownerId, name),
    statuses: owned,
    latestAt: owned[owned.length - 1]?.createdAt ?? new Date().toISOString(),
    allViewed: owned.every((status) => status.viewedByMe),
  }
}
