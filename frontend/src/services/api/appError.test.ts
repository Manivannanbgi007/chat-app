import { AxiosError, type AxiosResponse } from 'axios'
import { AppError, toAppError } from './appError'

// Helper: builds the error axios would throw for a given HTTP status.
function httpError(status: number, data?: unknown): AxiosError {
  const response = { status, data } as AxiosResponse
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, response)
}

describe('toAppError', () => {
  it('maps "no response" to a NETWORK error', () => {
    const error = toAppError(new AxiosError('Network Error', 'ERR_NETWORK'))
    expect(error.kind).toBe('NETWORK')
    expect(error.status).toBeNull()
  })

  it('maps 400 to VALIDATION and keeps field errors', () => {
    const error = toAppError(
      httpError(400, { success: false, message: 'Validation failed', data: { email: 'must be a valid email' } }),
    )
    expect(error.kind).toBe('VALIDATION')
    expect(error.message).toBe('Validation failed')
    expect(error.fieldErrors).toEqual({ email: 'must be a valid email' })
  })

  it.each([
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [429, 'RATE_LIMITED'],
    [500, 'SERVER'],
    [503, 'SERVER'],
  ])('maps status %i to %s', (status, kind) => {
    expect(toAppError(httpError(status)).kind).toBe(kind)
  })

  it('does not show raw server text for 5xx errors', () => {
    const error = toAppError(httpError(500, { message: 'NullPointerException at line 42' }))
    expect(error.message).not.toContain('NullPointer')
  })

  it('keeps the business errorCode, e.g. for a 409 or a 423', () => {
    const taken = toAppError(httpError(409, { success: false, message: 'Email exists', data: { errorCode: 'EMAIL_TAKEN' } }))
    expect(taken.errorCode).toBe('EMAIL_TAKEN')
    expect(taken.message).toBe('Email exists')

    expect(toAppError(httpError(423, { data: { errorCode: 'ACCOUNT_LOCKED' } })).errorCode).toBe('ACCOUNT_LOCKED')
    expect(toAppError(httpError(404)).errorCode).toBeNull()
  })

  it('does not treat the errorCode of a 400 as a form field', () => {
    const error = toAppError(httpError(400, { data: { errorCode: 'INVALID_OTP' } }))
    expect(error.errorCode).toBe('INVALID_OTP')
    expect(error.fieldErrors).toEqual({})
  })

  it('returns an existing AppError unchanged', () => {
    const original = new AppError('FORBIDDEN', 'nope', 403)
    expect(toAppError(original)).toBe(original)
  })

  it('maps unknown errors to UNKNOWN', () => {
    expect(toAppError(new Error('boom')).kind).toBe('UNKNOWN')
  })

  it('keeps the extra error data, e.g. the refused people of GROUP_ADD_NOT_ALLOWED', () => {
    const error = toAppError(
      httpError(403, { success: false, message: 'no', data: { errorCode: 'GROUP_ADD_NOT_ALLOWED', userIds: ['u-1'] } }),
    )
    expect(error.errorCode).toBe('GROUP_ADD_NOT_ALLOWED')
    expect(error.details.userIds).toEqual(['u-1'])
  })
})
