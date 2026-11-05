// Shapes of the user-service responses (see API_DOCUMENTATION.md section 3).

export type Visibility = 'EVERYONE' | 'CONTACTS' | 'NOBODY'

export interface MyProfile {
  id: string
  username: string
  displayName: string
  email: string
  phone: string | null
  profileImageUrl: string | null
  about: string | null
  accountStatus: string
  createdAt: string
  updatedAt: string
}

// What other people may see. about / profileImageUrl are null when privacy hides them.
export interface PublicProfile {
  id: string
  username: string
  displayName: string
  profileImageUrl: string | null
  about: string | null
}

export interface Contact {
  id: string
  contactUserId: string
  nickname: string | null
  createdAt: string
  user: PublicProfile
}

export interface Privacy {
  lastSeen: Visibility
  profilePhoto: Visibility
  about: Visibility
  groupAdd: Visibility
  readReceipts: boolean
}
