// Lets code that is NOT a React component (for example a click on a desktop notification) move to another page
// without reloading the app. AppLayout registers react-router's navigate function here.

let navigator = null

// Returns the function that unregisters it again.
export function registerNavigator(navigate) {
  navigator = navigate
  return () => {
    if (navigator === navigate) navigator = null
  }
}

export function navigateTo(path) {
  if (navigator) navigator(path)
  else window.location.assign(path) // no router yet: a normal page load still works
}
