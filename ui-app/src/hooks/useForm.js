import { useCallback, useRef, useState } from 'react'

// A small form helper: it keeps the values, the validation messages and the "submitting" flag of one form.
// (The TypeScript version of the app used the react-hook-form library for this; plain React state is enough here.)
//
//   const form = useForm({ initialValues: { email: '', password: '' }, validate: validateLogin })
//
//   <form onSubmit={form.handleSubmit(onValid)} noValidate>
//     <input {...form.register('email')} />            // text inputs, selects, textareas
//     <input type="checkbox" {...form.registerCheckbox('rememberMe')} />
//     <p>{form.errors.email}</p>
//     <button disabled={form.isSubmitting}>Log in</button>
//
// `validate(values)` returns an object { fieldName: 'message' } (empty when everything is fine).
// `onValid(values)` is only called when there are no messages; while it runs, isSubmitting is true.
export function useForm({ initialValues, validate }) {
  const [values, setValues] = useState(initialValues)
  const [errors, setErrors] = useState({})
  const [isSubmitting, setIsSubmitting] = useState(false)
  // The first values are kept in a ref so that `reset` stays the same function on every render
  // (pages call it inside a useEffect that lists it as a dependency).
  const initialRef = useRef(initialValues)

  function setValue(name, value) {
    setValues((current) => ({ ...current, [name]: value }))
    // The message under a field disappears as soon as the user changes that field.
    setErrors((current) => (current[name] ? { ...current, [name]: undefined } : current))
  }

  // Props for an <input>, <select> or <textarea>.
  function register(name) {
    return {
      name,
      value: values[name] ?? '',
      onChange: (event) => setValue(name, event.target.value),
    }
  }

  // Props for an <input type="checkbox">.
  function registerCheckbox(name) {
    return {
      name,
      checked: Boolean(values[name]),
      onChange: (event) => setValue(name, event.target.checked),
    }
  }

  // Wraps the submit handler: checks the values first, then calls `onValid`.
  function handleSubmit(onValid) {
    return async (event) => {
      event.preventDefault()
      const found = validate ? validate(values) : {}
      setErrors(found)
      if (Object.keys(found).length > 0) return
      setIsSubmitting(true)
      try {
        await onValid(values)
      } finally {
        setIsSubmitting(false)
      }
    }
  }

  // Shows a message under one field (used for errors that come back from the server, e.g. "email already used").
  function setError(name, message) {
    setErrors((current) => ({ ...current, [name]: message }))
  }

  // Replaces every value (used to fill a form once the data has loaded) and clears the messages.
  const reset = useCallback((nextValues = initialRef.current) => {
    setValues(nextValues)
    setErrors({})
  }, [])

  // getValues() = all values, getValues('email') = one value.
  function getValues(name) {
    return name === undefined ? values : values[name]
  }

  return { values, errors, isSubmitting, register, registerCheckbox, handleSubmit, setError, reset, getValues }
}
