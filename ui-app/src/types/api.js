// The files in src/types hold NO code. They document the shapes of the data the backend sends, as JSDoc typedefs
// (the JavaScript way of writing down a type). Editors such as VS Code read them for autocomplete when a function
// is tagged with @param / @returns, and they are the quickest place to look up "what fields does X have?".

/**
 * Every backend REST response uses this same envelope (ApiResponseDTO).
 * @typedef {object} ApiResponse
 * @property {boolean} success
 * @property {string} message
 * @property {*} data
 * @property {string} timestamp
 */

/**
 * Data returned by GET /api/auth/ping
 * @typedef {object} PingData
 * @property {string} service
 * @property {string} status
 */

export {}
