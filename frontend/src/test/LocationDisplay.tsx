import { useLocation } from 'react-router-dom'

// Shows the current path and router state, so tests can check where the app navigated.
export default function LocationDisplay() {
  const location = useLocation()
  return (
    <div>
      <p data-testid="path">{location.pathname + location.search}</p>
      <p data-testid="state">{JSON.stringify(location.state ?? null)}</p>
    </div>
  )
}
