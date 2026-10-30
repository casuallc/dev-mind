// CAP-103 细粒度权限判定：菜单/页签按节点编码可见，操作按钮额外受基础角色写权限约束。
// 未加载完成前一律放行，避免首屏按钮闪烁；最终安全边界始终在后端。
import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react'
import { canWrite, getUserSnapshot, isAdmin, subscribeAuth } from './authStore'
import { useMenus } from './useMenus'
import type { MenuItem } from './types'

export interface Permissions {
  loaded: boolean
  /** 菜单/页签节点是否授予当前角色（ADMIN 恒 true） */
  can: (menuKey: string) => boolean
  /** 操作按钮是否可用：节点已授予，且写操作要求基础角色可写（非 VIEWER） */
  canAction: (menuKey: string) => boolean
  /** 当前角色在某一 zone 下可用的菜单节点（导航渲染用） */
  menusOf: (zone: string) => MenuItem[]
}

export function usePermissions(): Permissions {
  const { items, loaded } = useMenus()
  useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const admin = isAdmin()
  const writable = canWrite()

  const nodes = useMemo(
    () => new Map(items.map((item) => [item.menuKey, item] as const)),
    [items],
  )

  const can = useCallback(
    (menuKey: string) => {
      if (admin) return true
      if (!loaded) return true
      return nodes.has(menuKey)
    },
    [admin, loaded, nodes],
  )

  const canAction = useCallback(
    (menuKey: string) => {
      if (admin) return true
      if (!loaded) return true
      const node = nodes.get(menuKey)
      if (!node) return false
      return !node.writeAction || writable
    },
    [admin, loaded, nodes, writable],
  )

  const menusOf = useCallback(
    (zone: string) => items.filter((item) => item.zone === zone && item.nodeType === 'MENU'),
    [items],
  )

  return { loaded, can, canAction, menusOf }
}

/**
 * 页内页签权限：按节点编码过滤可用页签，当前页签失权时回落到第一个可用项。
 * all 与 permissionOf 应为模块级常量，保证判定稳定。
 */
export function usePermittedView<T extends string>(
  all: readonly T[],
  permissionOf: (view: T) => string,
  current: T,
  onChange: (view: T) => void,
  enabled = true,
): T[] {
  const { can, loaded } = usePermissions()
  const permitted = all.filter((view) => can(permissionOf(view)))
  const signature = permitted.join('|')
  useEffect(() => {
    if (!enabled || !loaded || permitted.length === 0) return
    if (!permitted.includes(current)) onChange(permitted[0])
    // signature 代替数组依赖：permitted 每次渲染都是新数组
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, loaded, signature, current])
  return permitted
}
