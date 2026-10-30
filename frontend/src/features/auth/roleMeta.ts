import type { RoleView } from './types'

/** 基础接口权限级别的展示口径（与后端 SecurityConfig 的粗粒度规则一致） */
export const BASE_ROLE_META: Record<string, { color: string; text: string }> = {
  ADMIN: { color: 'red', text: 'ADMIN（全权限）' },
  DEVELOPER: { color: 'blue', text: 'DEVELOPER（业务读写）' },
  VIEWER: { color: 'default', text: 'VIEWER（只读）' },
}

/** 自定义角色可选的基础权限：ADMIN 仅保留给内置管理员角色 */
export const CUSTOM_BASE_ROLE_OPTIONS = [
  { value: 'DEVELOPER', label: 'DEVELOPER（业务读写）' },
  { value: 'VIEWER', label: 'VIEWER（只读）' },
]

export function baseRoleMeta(baseRole: string) {
  return BASE_ROLE_META[baseRole] ?? { color: 'default', text: baseRole }
}

/** 展示用角色名：优先可管理角色名，旧数据退化为角色编码 */
export function displayRole(user: {
  role: string
  roleCode?: string | null
  roleName?: string | null
}) {
  return user.roleName || user.roleCode || user.role
}

export function roleSelectOptions(roles: RoleView[]) {
  if (roles.length === 0) {
    return [
      { value: 'ADMIN', label: 'ADMIN（全权限）' },
      { value: 'DEVELOPER', label: 'DEVELOPER（业务读写）' },
      { value: 'VIEWER', label: 'VIEWER（只读）' },
    ]
  }
  return roles.map((role) => ({
    value: role.code,
    label: `${role.name}（${baseRoleMeta(role.baseRole).text}）`,
  }))
}
