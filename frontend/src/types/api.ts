// Every backend REST response uses this same envelope (ApiResponseDTO).
export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
  timestamp: string
}

// Data returned by GET /api/auth/ping
export interface PingData {
  service: string
  status: string
}
