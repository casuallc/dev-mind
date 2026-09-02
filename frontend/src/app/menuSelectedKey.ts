// 菜单选中态：路径前缀 → 菜单 key（特殊的在前，遍历取首个匹配）。
// 工作台（AppLayout）与后台（AdminLayout）共用，新增页面只需在此登记一行。
const SELECT_PREFIXES: Array<[string, string]> = [
  ['/admin/projects', '/admin/projects'], // 列表 + 设置子路由
  ['/admin/docs', '/admin/docs'], // 列表 + 编辑器
  ['/projects/', '/requirements'], // /projects/:id/requirements/:rid → 需求
  ['/sessions/', '/sessions'],
  ['/requirements', '/requirements'],
  ['/builds', '/builds'],
  ['/deployments', '/deployments'],
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
