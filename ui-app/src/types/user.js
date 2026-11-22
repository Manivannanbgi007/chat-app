// Shapes of the user-service responses (see API_DOCUMENTATION.md section 3).

/** @typedef {'EVERYONE' | 'CONTACTS' | 'NOBODY'} Visibility */

/**
 * @typedef {object} MyProfile
 * @property {string} id
 * @property {string} username
 * @property {string} displayName
 * @property {string} email
 * @property {string | null} phone
 * @property {string | null} profileImageUrl
 * @property {string | null} about
 * @property {string} accountStatus
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * What other people may see. about / profileImageUrl are null when privacy hides them.
 * @typedef {object} PublicProfile
 * @property {string} id
 * @property {string} username
 * @property {string} displayName
 * @property {string | null} profileImageUrl
 * @property {string | null} about
 */

/**
 * @typedef {object} Contact
 * @property {string} id
 * @property {string} contactUserId
 * @property {string | null} nickname
 * @property {string} createdAt
 * @property {PublicProfile} user
 */

/**
 * @typedef {object} Privacy
 * @property {Visibility} lastSeen
 * @property {Visibility} profilePhoto
 * @property {Visibility} about
 * @property {Visibility} groupAdd
 * @property {boolean} readReceipts
 */

export {}
