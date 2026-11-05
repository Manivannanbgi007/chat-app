import { z } from 'zod'

// Form rules. They mirror the backend rules (API_DOCUMENTATION.md, section 2),
// so most mistakes are caught before a request is sent. The server still checks everything.

const PHONE = /^\+[1-9]\d{7,14}$/

const password = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(72, 'Password must be at most 72 characters')
  .regex(/[A-Za-z]/, 'Password must contain a letter')
  .regex(/\d/, 'Password must contain a number')

export const loginSchema = z.object({
  identifier: z.string().trim().min(1, 'Enter your email or phone number'),
  password: z.string().min(1, 'Enter your password'),
  rememberMe: z.boolean(),
})
export type LoginForm = z.infer<typeof loginSchema>

export const registerSchema = z
  .object({
    username: z.string().regex(/^[A-Za-z0-9_.]{3,30}$/, '3-30 characters: letters, numbers, _ or .'),
    displayName: z.string().trim().min(1, 'Enter your name').max(50, 'At most 50 characters'),
    email: z.email('Enter a valid email address').max(254, 'Email is too long'),
    phone: z.string().regex(PHONE, 'Use international format, e.g. +919876543210').or(z.literal('')),
    password,
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })
export type RegisterForm = z.infer<typeof registerSchema>

export const verifySchema = z.object({
  identifier: z.string().trim().min(1, 'Enter your email'),
  otp: z.string().regex(/^\d{6}$/, 'Enter the 6-digit code'),
})
export type VerifyForm = z.infer<typeof verifySchema>

export const forgotPasswordSchema = z.object({
  identifier: z.string().trim().min(1, 'Enter your email or phone number'),
})
export type ForgotPasswordForm = z.infer<typeof forgotPasswordSchema>

export const resetPasswordSchema = z
  .object({
    newPassword: password,
    confirmPassword: z.string(),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })
export type ResetPasswordForm = z.infer<typeof resetPasswordSchema>
