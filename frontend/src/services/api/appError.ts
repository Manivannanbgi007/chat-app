import { isAxiosError } from 'axios'
import type { ApiResponse } from '../../types/api'

export type AppErrorKind =
  | 'NETWORK'
  | 'VALIDATION'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'RATE_LIMITED'
  | 'SERVER'
  | 'UNKNOWN'

// The ONE error type the UI deals with. Every failed API call becomes an AppError.
export class AppError extends Error {
  kind: AppErrorKind
  status: number | null
  // For validation errors: { fieldName: "message" }
  fieldErrors: Record<string, string>
  // Machine-readable business error from the backend, e.g. "EMAIL_TAKEN" (see API_DOCUMENTATION.md)
  errorCode: string | null
  // Everything else the server put in "data" next to the errorCode,
  // e.g. { userIds: [...] } for GROUP_ADD_NOT_ALLOWED.
  details: Record<string, unknown>

  constructor(
    kind: AppErrorKind,
    message: string,
    status: number | null = null,
    fieldErrors: Record<string, string> = {},
    errorCode: string | null = null,
    details: Record<string, unknown> = {},
  ) {
    super(message)
    this.name = 'AppError'
    this.kind = kind
    this.status = status
    this.fieldErrors = fieldErrors
    this.errorCode = errorCode
    this.details = details
  }
}

// Turns anything thrown by axios into an AppError with a user-friendly message.
export function toAppError(error: unknown): AppError {
  if (error instanceof AppError) {
    return error
  }

  if (!isAxiosError(error)) {
    return new AppError('UNKNOWN', 'Something went wrong. Please try again.')
  }

  // No response at all: server down, no internet, timeout, blocked by CORS...
  if (!error.response) {
    return new AppError('NETWORK', 'Cannot reach the server. Check your connection and try again.')
  }

  const status = error.response.status
  const body = error.response.data as Partial<ApiResponse<unknown>> | undefined
  const serverMessage = typeof body?.message === 'string' ? body.message : null
  const errorCode = readErrorCode(body?.data)
  const details = readDetails(body?.data)

  switch (status) {
    case 400:
      return new AppError(
        'VALIDATION',
        serverMessage ?? 'Some of the entered values are not valid.',
        status,
        readFieldErrors(body?.data),
        errorCode,
        details,
      )
    case 401:
      return new AppError('UNAUTHORIZED', 'Your session has expired. Please log in again.', status, {}, errorCode)
    case 403:
      return new AppError('FORBIDDEN', 'You do not have permission to do that.', status, {}, errorCode, details)
    case 404:
      return new AppError('NOT_FOUND', serverMessage ?? 'We could not find what you asked for.', status, {}, errorCode, details)
    case 429:
      return new AppError(
        'RATE_LIMITED',
        'Too many requests. Please wait a moment and try again.',
        status,
        {},
        errorCode,
      )
    default:
      if (status >= 500) {
        return new AppError('SERVER', 'The server had a problem. Please try again later.', status)
      }
      return new AppError(
        'UNKNOWN',
        serverMessage ?? 'Something went wrong. Please try again.',
        status,
        {},
        errorCode,
        details,
      )
  }
}

// Business errors carry a code in "data": { errorCode: "EMAIL_TAKEN" }.
function readErrorCode(data: unknown): string | null {
  if (data && typeof data === 'object' && 'errorCode' in data) {
    const code = (data as { errorCode: unknown }).errorCode
    return typeof code === 'string' ? code : null
  }
  return null
}

// The rest of "data" when it is an object (for example { errorCode, userIds }).
function readDetails(data: unknown): Record<string, unknown> {
  return data && typeof data === 'object' && !Array.isArray(data) ? { ...(data as Record<string, unknown>) } : {}
}

// The backend puts validation errors in "data" as { field: "message" }.
function readFieldErrors(data: unknown): Record<string, string> {
  const result: Record<string, string> = {}
  if (data && typeof data === 'object') {
    for (const [field, message] of Object.entries(data)) {
      if (typeof message === 'string' && field !== 'errorCode') {
        result[field] = message
      }
    }
  }
  return result
}
