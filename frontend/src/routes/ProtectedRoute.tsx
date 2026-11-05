import { Navigate, Outlet, useLocation } from 'react-router-dom'
import LoadingScreen from '../components/common/LoadingScreen'
import { useAuthStore } from '../store/authStore'

// Pages inside this route need a logged-in user.
export default function ProtectedRoute() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const restoring = useAuthStore((state) => state.restoring)
  const location = useLocation()

  // Start-up: we are still swapping the stored refresh token for an access token.
  if (restoring) {
    return <LoadingScreen />
  }

  if (!accessToken) {
    // Remember where the user wanted to go, so login can send them back there.
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
