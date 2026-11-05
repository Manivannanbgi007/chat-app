// Shapes of the auth-service responses (see API_DOCUMENTATION.md section 2).

export interface AuthUser {
  id: string
  username: string
  displayName: string
  email: string
}

// Returned by login and refresh.
export interface AuthTokens {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: AuthUser
}

// One logged-in device, from GET /api/auth/sessions.
export interface SessionInfo {
  id: string
  deviceName: string | null
  ipAddress: string | null
  createdAt: string
  lastActiveAt: string
  current: boolean
}

export interface RegisterRequest {
  username: string
  email: string
  phone?: string
  password: string
  displayName: string
}

export interface RegisterResult {
  userId: string
  email: string
  verificationRequired: boolean
}
