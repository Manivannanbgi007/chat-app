// Shapes of the status-service answers (status / stories, API_DOCUMENTATION.md section 7.4).
import type { PublicProfile } from './user'

export type StatusType = 'TEXT' | 'IMAGE' | 'VIDEO'

// A short copy of the file's details (the file itself is fetched through a signed link).
export interface StatusMedia {
  kind: 'IMAGE' | 'VIDEO'
  mimeType: string
  width: number | null
  height: number | null
  durationSeconds: number | null
  hasThumbnail: boolean
}

export interface Status {
  id: string
  ownerId: string
  type: StatusType
  text: string | null // TEXT only
  backgroundColor: string | null // TEXT only, one of STATUS_COLORS
  caption: string | null // IMAGE / VIDEO only
  media: StatusMedia | null
  createdAt: string
  expiresAt: string // createdAt + 24 hours
  viewedByMe: boolean // always true for my own statuses
}

// One of MY statuses: how many people (that I may see) viewed it.
export interface MyStatus extends Status {
  viewCount: number
}

// Everything one person posted that I may see, oldest first.
export interface StatusGroup {
  owner: PublicProfile
  statuses: Status[]
  latestAt: string
  allViewed: boolean
}

export interface StatusViewer {
  user: PublicProfile
  viewedAt: string
}

// Who saw one of my statuses. viewersHidden = my own read receipts are off, so I see nobody.
export interface StatusViewers {
  viewersHidden: boolean
  viewCount: number
  viewers: StatusViewer[] // newest first
}

export type StatusPrivacyMode = 'CONTACTS' | 'CONTACTS_EXCEPT' | 'ONLY_SHARE_WITH'

export interface StatusPrivacy {
  mode: StatusPrivacyMode
  userIds: string[] // the excepted people (CONTACTS_EXCEPT) or the chosen people (ONLY_SHARE_WITH)
}

// The body of POST /api/statuses.
export type CreateStatusBody =
  | { type: 'TEXT'; text: string; backgroundColor: string; idempotencyKey: string }
  | { type: 'IMAGE' | 'VIDEO'; mediaId: string; caption?: string; idempotencyKey: string }
