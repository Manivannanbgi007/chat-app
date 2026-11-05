// Shape of the presence-service answer (see API_DOCUMENTATION.md section 7.1).

// GET /api/presence/{userId}
export interface Presence {
  userId: string
  // false = the person's privacy settings (or a block) hide the status from me: show nothing.
  visible: boolean
  // null when not visible
  online: boolean | null
  // UTC time of the last heartbeat / "offline" call, null when not visible or never seen
  lastSeenAt: string | null
}
