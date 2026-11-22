// Rules of the "Edit your profile" form (see validations/authValidation.js for how these functions work).
export function validateProfile(values) {
  const errors = {}
  const displayName = values.displayName.trim()
  if (!displayName) errors.displayName = 'Enter your name'
  else if (displayName.length > 50) errors.displayName = 'At most 50 characters'
  if (values.about.length > 139) errors.about = 'At most 139 characters'
  return errors
}
