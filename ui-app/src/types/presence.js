// Shape of the presence-service answer (see API_DOCUMENTATION.md section 7.1).

/**
 * GET /api/presence/{userId}
 * @typedef {object} Presence
 * @property {string} userId
 * @property {boolean} visible - false = the person's privacy settings (or a block) hide the status from me: show nothing.
 * @property {boolean | null} online - null when not visible
 * @property {string | null} lastSeenAt - UTC time of the last heartbeat / "offline" call, null when not visible or never seen
 */

export {}
