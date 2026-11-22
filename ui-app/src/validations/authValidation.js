// Form rules. They mirror the backend rules (API_DOCUMENTATION.md, section 2),
// so most mistakes are caught before a request is sent. The server still checks everything.
//
// Every validate function takes the form values and returns { fieldName: 'message' } for the fields that are wrong
// (an empty object means the form is fine). They are used with hooks/useForm.js.

const PHONE = /^\+[1-9]\d{7,14}$/
const USERNAME = /^[A-Za-z0-9_.]{3,30}$/
const OTP = /^\d{6}$/
// A simple email check: something@something.something (the server does the strict check).
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// The password rules, shared by "register" and "reset password". Returns a message or null.
function passwordError(password) {
  if (password.length < 8) return 'Password must be at least 8 characters'
  if (password.length > 72) return 'Password must be at most 72 characters'
  if (!/[A-Za-z]/.test(password)) return 'Password must contain a letter'
  if (!/\d/.test(password)) return 'Password must contain a number'
  return null
}

export function validateLogin(values) {
  const errors = {}
  if (!values.identifier.trim()) errors.identifier = 'Enter your email, phone number or username'
  if (!values.password) errors.password = 'Enter your password'
  return errors
}

export function validateRegister(values) {
  const errors = {}
  if (!USERNAME.test(values.username)) errors.username = '3-30 characters: letters, numbers, _ or .'

  const displayName = values.displayName.trim()
  if (!displayName) errors.displayName = 'Enter your name'
  else if (displayName.length > 50) errors.displayName = 'At most 50 characters'

  if (!EMAIL.test(values.email)) errors.email = 'Enter a valid email address'
  else if (values.email.length > 254) errors.email = 'Email is too long'

  // The phone number is optional, but when it is typed it must be in international format.
  if (values.phone !== '' && !PHONE.test(values.phone)) errors.phone = 'Use international format, e.g. +919876543210'

  const password = passwordError(values.password)
  if (password) errors.password = password
  if (values.password !== values.confirmPassword) errors.confirmPassword = 'Passwords do not match'
  return errors
}

export function validateVerify(values) {
  const errors = {}
  if (!values.identifier.trim()) errors.identifier = 'Enter your email'
  if (!OTP.test(values.otp)) errors.otp = 'Enter the 6-digit code'
  return errors
}

export function validateForgotPassword(values) {
  const errors = {}
  if (!values.identifier.trim()) errors.identifier = 'Enter your email or phone number'
  return errors
}

export function validateResetPassword(values) {
  const errors = {}
  const password = passwordError(values.newPassword)
  if (password) errors.newPassword = password
  if (values.newPassword !== values.confirmPassword) errors.confirmPassword = 'Passwords do not match'
  return errors
}
