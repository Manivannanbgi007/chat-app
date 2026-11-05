import { z } from 'zod'

export const profileSchema = z.object({
  displayName: z.string().trim().min(1, 'Enter your name').max(50, 'At most 50 characters'),
  about: z.string().max(139, 'At most 139 characters'),
})
export type ProfileForm = z.infer<typeof profileSchema>
