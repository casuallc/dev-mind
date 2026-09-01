import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { Button, Result } from 'antd'
import { isAdmin, isAuthed } from './authStore'

/** 管理后台路由守卫：无 token → /login；非 ADMIN → 403 页（给返回工作台入口，不静默跳转）。 */
export default function RequireAdmin({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  if (!isAuthed()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (!isAdmin()) {
    return (
      <Result
        status="403"
        title="无权限"
        subTitle="管理后台仅 ADMIN 可用"
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            返回工作台
          </Button>
        }
      />
    )
  }
  return <>{children}</>
}
