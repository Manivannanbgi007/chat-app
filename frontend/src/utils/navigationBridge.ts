// Lets code that is NOT a React component (for example a click on a desktop notification) move to another page
// without reloading the app. AppLayout registers react-router's navigate function here.

let navigator: ((path: string) => void) | null = null

// Returns the function that unregisters it again.
export function registerNavigator(navigate: (path: string) => void): () => void {
  navigator = navigate
  return () => {
    if (navigator === navigate) navigator = null
  }
}

export function navigateTo(path: string): void {
  if (navigator) navigator(path)
  else window.location.assign(path) // no router yet: a normal page load still works
}
