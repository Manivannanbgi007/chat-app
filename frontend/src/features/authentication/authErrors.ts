import type { AppError } from '../../services/api/appError'

// Friendly messages for the business error codes in API_DOCUMENTATION.md.
// Anything else falls back to the message already prepared by toAppError.
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Incorrect email/phone or password.',
  EMAIL_NOT_VERIFIED: 'Please verify your email first.',
  ACCOUNT_LOCKED: 'Too many failed attempts. Your account is locked for 15 minutes.',
  INVALID_OTP: 'That code is not correct or has expired.',
  OTP_ATTEMPTS_EXCEEDED: 'Too many wrong attempts. Please request a new code.',
  OTP_RATE_LIMITED: 'Too many codes requested. Please wait a moment before asking for another one.',
  INVALID_RESET_TOKEN: 'This reset link is invalid or has expired. Please request a new one.',
  EMAIL_TAKEN: 'An account with this email already exists.',
  PHONE_TAKEN: 'An account with this phone number already exists.',
  USERNAME_TAKEN: 'This username is already taken.',
  ALREADY_CONTACT: 'This person is already in your contacts.',
}

export function friendlyAuthError(error: AppError): string {
  return (error.errorCode && MESSAGES[error.errorCode]) || error.message
}
