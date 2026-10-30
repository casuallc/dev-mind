// CAP-61 FR-05 弱守卫：直敲无权限菜单 URL 时给提示页（防呆，不是安全边界；
// 敏感接口仍由后端过滤器链独立拦截）。非菜单页面（通知/更新日志等）直接放行。
import { Button, Result, Spin } from 'antd'
import type { ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { menuSelectedKey } from './menuSelectedKey'
import { useMenus } from '../features/auth/useMenus'

export default function MenuAccessGuard({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { items, knownPaths, loaded, error } = useMenus()

  // 加载中先等清单，避免闪现无权限页面；加载失败放行（后端仍是安全边界）
  if (!loaded && !error) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin />
      </div>
    )
  }
  if (!loaded) return <>{children}</>

  const selected = menuSelectedKey(location.pathname)
  if (!knownPaths.includes(selected) || items.some((m) => m.path === selected)) {
    return <>{children}</>
  }

  const home = items.find((m) => m.path === '/home')
  const fallback = home?.path ?? items[0]?.path ?? '/login'
  return (
    <Result
      status="403"
      title="无权限访问"
      subTitle="当前账号没有该菜单权限，请联系管理员在「用户管理 → 角色」中调整"
      extra={
        <Button type="primary" onClick={() => navigate(fallback, { replace: true })}>
          {home ? '返回工作台' : '返回可用页面'}
        </Button>
      }
    />
  )
}
