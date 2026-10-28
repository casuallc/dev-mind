import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { Button, Result, Spin } from 'antd'
import { isAdmin, isAuthed } from './authStore'
import { useMenus } from './useMenus'

/**
 * CAP-61 起后台入口不再仅限 ADMIN：ADMIN 恒放行；
 * 其他角色有任一后台菜单授权即可进入，具体页面再由 MenuAccessGuard 按 path 判定。
 */
export default function RequireAdmin({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { items, loaded, error } = useMenus()
  if (!isAuthed()) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (isAdmin()) {
    return <>{children}</>
  }
  if (!loaded && !error) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin />
      </div>
    )
  }
  if (!items.some((m) => m.zone === 'ADMIN')) {
    return (
      <Result
        status="403"
        title="无权限"
        subTitle="当前账号没有后台菜单权限，请联系管理员在「菜单权限」中授权"
        extra={
          <Button type="primary" onClick={() => navigate('/home')}>
            返回工作台
          </Button>
        }
      />
    )
  }
  return <>{children}</>
}
