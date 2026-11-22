// Shapes of the call-signaling-service answers (API_DOCUMENTATION.md section 7.3)
// and of the live call events on /user/queue/calls (WEBSOCKET_DESIGN.md section 9).

/** @typedef {'AUDIO' | 'VIDEO'} CallType */

/**
 * RINGING -> ACCEPTED -> ENDED, or from RINGING: REJECTED / MISSED. BUSY is final at once.
 * @typedef {'RINGING' | 'ACCEPTED' | 'ENDED' | 'MISSED' | 'REJECTED' | 'BUSY'} CallStatus
 */

/** @typedef {'HANGUP' | 'CONNECTION_LOST' | 'NO_ANSWER' | 'CANCELLED' | 'DECLINED' | 'BUSY'} CallEndReason */

/**
 * @typedef {object} Call
 * @property {string} id
 * @property {CallType} type
 * @property {CallStatus} status
 * @property {CallEndReason | null} endReason
 * @property {'OUTGOING' | 'INCOMING'} direction - from MY point of view
 * @property {string} callerId
 * @property {string} calleeId
 * @property {import('./user').PublicProfile} otherUser - the other person
 * @property {string} createdAt
 * @property {string | null} answeredAt
 * @property {string | null} endedAt
 * @property {number} durationSeconds
 */

/**
 * One entry for RTCPeerConnection's iceServers: a STUN server, or the TURN server with short-lived credentials.
 * @typedef {object} IceServerConfig
 * @property {string[]} urls
 * @property {string} [username]
 * @property {string} [credential]
 */

/**
 * POST /api/calls and POST /api/calls/{id}/accept
 * @typedef {object} CallSession
 * @property {Call} call
 * @property {IceServerConfig[]} iceServers
 */

/**
 * GET /api/calls/history
 * @typedef {object} CallHistoryPage
 * @property {Call[]} items
 * @property {number} page
 * @property {number} size
 * @property {number} totalItems
 * @property {boolean} hasMore
 */

// ---- signaling (the messages that set up the browser-to-browser connection) ----

/** @typedef {'OFFER' | 'ANSWER' | 'ICE'} SignalType */

/**
 * One ICE candidate ("a network address where you may reach me"), exactly as RTCIceCandidate describes it.
 * @typedef {object} IceCandidateData
 * @property {string} candidate
 * @property {string | null} sdpMid
 * @property {number | null} sdpMLineIndex
 */

/**
 * Body of POST /api/calls/{id}/signal
 * @typedef {object} SignalBody
 * @property {SignalType} type
 * @property {string} [sdp]
 * @property {IceCandidateData[]} [candidates]
 */

/**
 * data of a CALL_SIGNAL event
 * @typedef {object} CallSignalData
 * @property {string} callId
 * @property {string} fromUserId
 * @property {string} toDeviceId
 * @property {SignalType} signalType
 * @property {string | null} sdp
 * @property {IceCandidateData[] | null} candidates
 */

/**
 * The live events on /user/queue/calls. `type` decides what `data` holds:
 *   CALL_INCOMING -> Call,  CALL_ACCEPTED -> { call, calleeDeviceId },  CALL_ENDED -> Call,  CALL_SIGNAL -> CallSignalData
 * @typedef {object} CallEvent
 * @property {'CALL_INCOMING' | 'CALL_ACCEPTED' | 'CALL_ENDED' | 'CALL_SIGNAL'} type
 * @property {string} eventId
 * @property {*} data
 * @property {string} timestamp
 */

export {}
