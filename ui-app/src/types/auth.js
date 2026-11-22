// Shapes of the auth-service responses (see API_DOCUMENTATION.md section 2).

/**
 * @typedef {object} AuthUser
 * @property {string} id
 * @property {string} username
 * @property {string} displayName
 * @property {string} email
 */

/**
 * Returned by login and refresh.
 * @typedef {object} AuthTokens
 * @property {string} accessToken
 * @property {string} refreshToken
 * @property {'Bearer'} tokenType
 * @property {number} expiresIn
 * @property {AuthUser} user
 */

/**
 * One logged-in device, from GET /api/auth/sessions.
 * @typedef {object} SessionInfo
 * @property {string} id
 * @property {string | null} deviceName
 * @property {string | null} ipAddress
 * @property {string} createdAt
 * @property {string} lastActiveAt
 * @property {boolean} current
 */

/**
 * @typedef {object} RegisterRequest
 * @property {string} username
 * @property {string} email
 * @property {string} [phone]
 * @property {string} password
 * @property {string} displayName
 */

/**
 * @typedef {object} RegisterResult
 * @property {string} userId
 * @property {string} email
 * @property {boolean} verificationRequired
 */

export {}
