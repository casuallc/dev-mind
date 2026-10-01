// 菜单选中态：路径前缀 → 菜单 key（特殊的在前，遍历取首个匹配）。
// 工作台（AppLayout）与后台（AdminLayout）共用，新增页面只需在此登记一行。
const SELECT_PREFIXES: Array<[string, string]> = [
  // 智能决策（laya）：在线试分类/安装包是「决策记录」「服务实例」菜单下的页内视图，高亮归属菜单
  ['/admin/laya/playground', '/admin/laya/records'],
  ['/admin/laya/packages', '/admin/laya/instances'],
  ['/admin/agent/nodes', '/admin/agent/nodes'], // 列表 + 节点详情（/admin/agent/nodes/:id）
  ['/admin/projects', '/admin/projects'], // 列表 + 设置子路由
  ['/admin/docs', '/admin/docs'], // 列表 + 编辑器
  ['/admin/knowledge', '/admin/knowledge'], // 列表 + 库详情（/admin/knowledge/bases/:id）
  ['/projects/', '/requirements'], // /projects/:id/requirements/:rid → 需求
  ['/context', '/context'],
  ['/chats', '/chats'],
  ['/bookmarks', '/bookmarks'],
  ['/worklog', '/worklog'],
  ['/settings', '/settings'], // 一级导航「设置」（旧 /me/settings 已重定向）
  ['/requirements', '/requirements'],
  ['/builds', '/builds'],
  ['/deployments', '/deployments'],
  ['/releases', '/releases'],
  ['/tests', '/tests'],
  ['/overview', '/overview'],
  ['/notifications', '/notifications'],
]

export function menuSelectedKey(pathname: string): string {
  for (const [prefix, key] of SELECT_PREFIXES) {
    if (pathname.startsWith(prefix)) return key
  }
  return pathname
}

// 工作台顶部一级导航选中态：项目上下文各页与项目列表统一高亮「项目」（key=/overview）。
const TOPNAV_PREFIXES: Array<[string, string]> = [
  ['/home', '/home'],
  ['/chats', '/chats'],
  ['/bookmarks', '/bookmarks'],
  ['/worklog', '/worklog'],
  ['/settings', '/settings'], // 一级导航「设置」
  ['/overview', '/overview'],
  ['/sessions', '/overview'],
  ['/requirements', '/overview'],
  ['/context', '/overview'],
  ['/builds', '/overview'],
  ['/deployments', '/overview'],
  ['/releases', '/overview'],
  ['/tests', '/overview'],
  ['/projects', '/overview'],
]

export function topNavKey(pathname: string): string {
  for (const [prefix, key] of TOPNAV_PREFIXES) {
    if (pathname.startsWith(prefix)) return key
  }
  return ''
}
