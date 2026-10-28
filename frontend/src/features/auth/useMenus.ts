import { useEffect, useSyncExternalStore } from 'react'
import { getMenuSnapshot, loadMenus, subscribeMenus } from './menuStore'

/** CAP-61 菜单快照：组件挂载时确保已加载（store 内部去重），返回清单与重拉方法。 */
export function useMenus() {
  const snapshot = useSyncExternalStore(subscribeMenus, getMenuSnapshot)
  useEffect(() => {
    void loadMenus()
  }, [])
  return { ...snapshot, reload: () => loadMenus(true) }
}
