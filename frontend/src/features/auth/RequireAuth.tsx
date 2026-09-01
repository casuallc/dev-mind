import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { isAuthed } from './authStore'

/** CAP-01 路由守卫：无 token → /login（记住来源页，登录后可回跳）。 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  if (!isAuthed()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}
