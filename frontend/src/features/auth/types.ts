export interface AuthUser {
  id: string
  username: string
  displayName: string
  description?: string | null
  /** 基础接口权限级别：ADMIN / DEVELOPER / VIEWER */
  role: string
  /** 可管理角色编码（roles.code），菜单权限按它判定；旧数据缺省时同 role */
  roleCode?: string | null
  roleName?: string | null
  status: string
  createdAt?: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: AuthUser
}

/** CAP-61 菜单项（目录由后端代码登记，前端只按下发清单渲染）；CAP-103 扩展为 MENU/TAB/ACTION 三级 */
export interface MenuItem {
  menuKey: string
  title: string
  path: string
  zone: 'TOP' | 'PROJECT' | 'ADMIN' | string
  groupName?: string | null
  icon?: string | null
  sortNo: number
  retired: boolean
  /** 节点类型：MENU 导航菜单 / TAB 页面页签 / ACTION 操作按钮 */
  nodeType: 'MENU' | 'TAB' | 'ACTION' | string
  /** 页签/按钮所属菜单或页签的权限编码；菜单为 null */
  parentKey?: string | null
  /** ACTION 是否为写操作（VIEWER 基础角色不可用） */
  writeAction: boolean
}

/** 可管理角色：baseRole 决定接口读写级别，menuKeys 决定可见菜单 */
export interface RoleView {
  code: string
  name: string
  description?: string | null
  builtin: boolean
  baseRole: string
  userCount: number
  menuKeys: string[]
  createdAt?: string
  updatedAt?: string
}

/** 当前用户可见菜单；knownPaths = 全量在架菜单路径（弱守卫区分非菜单页面） */
export interface VisibleMenus {
  items: MenuItem[]
  knownPaths: string[]
}

/** CAP-35 我的第三方账号视图：一行 = 一个 ENABLED 平台实例 + 我的绑定状态（不含 secret 明文） */
export interface PlatformAccount {
  integrationId: number
  integrationType: 'GITLAB' | 'GITHUB' | 'JIRA' | string
  integrationName: string
  baseUrl: string
  bound: boolean
  authType?: string | null
  /** 仅 Jira BASIC 回显（登录名非敏感） */
  username?: string | null
  hasSecret: boolean
  gitAuthorName?: string | null
  gitAuthorEmail?: string | null
  updatedAt?: string | null
}
