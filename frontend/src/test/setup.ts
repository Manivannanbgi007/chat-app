// Adds matchers such as toBeInTheDocument() to every test.
import '@testing-library/jest-dom/vitest'

// jsdom has no object URLs (blob: links to local files): a tiny stand-in so previews can be tested.
let blobCounter = 0
if (!URL.createObjectURL) {
  URL.createObjectURL = () => `blob:test-${++blobCounter}`
  URL.revokeObjectURL = () => undefined
}

// Every test starts with empty browser storage (tokens, theme).
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})
