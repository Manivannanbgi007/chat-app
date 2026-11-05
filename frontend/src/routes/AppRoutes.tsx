import { Navigate, Route, Routes } from 'react-router-dom'
import AppLayout from '../layouts/AppLayout'
import MainLayout from '../layouts/MainLayout'
import ForgotPasswordPage from '../pages/auth/ForgotPasswordPage'
import LoginPage from '../pages/auth/LoginPage'
import RegisterPage from '../pages/auth/RegisterPage'
import ResetPasswordPage from '../pages/auth/ResetPasswordPage'
import VerifyPage from '../pages/auth/VerifyPage'
import ContactsPage from '../pages/contacts/ContactsPage'
import CallsPage from '../pages/calls/CallsPage'
import ChatsPage from '../pages/chat/ChatsPage'
import NotificationsPage from '../pages/notifications/NotificationsPage'
import NotFoundPage from '../pages/NotFoundPage'
import ProfilePage from '../pages/profile/ProfilePage'
import SettingsPage from '../pages/settings/SettingsPage'
import StatusPage from '../pages/status/StatusPage'
import SystemStatusPage from '../pages/SystemStatusPage'
import ProtectedRoute from './ProtectedRoute'

// All page routes in one place.
export default function AppRoutes() {
  return (
    <Routes>
      {/* Public pages (no login needed) */}
      <Route element={<MainLayout />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        {/* backend connection check (Phase 1; /status since Phase 8 is the status / stories page) */}
        <Route path="/system-status" element={<SystemStatusPage />} />
      </Route>

      {/* Pages for logged-in users */}
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Navigate to="/chats" replace />} />
          <Route path="/chats" element={<ChatsPage />} />
          <Route path="/chats/:conversationId" element={<ChatsPage />} />
          <Route path="/status" element={<StatusPage />} />
          <Route path="/calls" element={<CallsPage />} />
          <Route path="/contacts" element={<ContactsPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
      </Route>

      <Route element={<MainLayout />}>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
