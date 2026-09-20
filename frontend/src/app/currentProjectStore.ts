// 当前项目上下文：工作台以某个具体项目为主线，projectId 不再只靠 URL 传递。
// 模块级轻量 store（仿 authStore.ts），localStorage 持久化 + storage 事件多标签页同步。
// 只存 id，项目对象由 useProject(id) 按需加载，避免 localStorage 副本陈旧。
const KEY = 'devmind.currentProjectId'

let currentId: string | null = localStorage.getItem(KEY)
// 项目列表是否已加载过（由 ProjectSwitcher 上报），供 ProjectContextGate 区分「加载中」与「真无项目」
let projectsLoaded = false
// 版本号：任何字段变化都自增，供订阅方（如 ProjectContextGate）判断是否需要重渲染。
// 只订阅 currentId 的组件会漏掉 projectsLoaded 的变化——bootstrap 拿到的项目与持久化值相同时
// 快照不变，React 会跳过重渲染，Gate 就一直停在加载态（硬加载项目页必现）。
let revision = 0
const listeners = new Set<() => void>()

function notify() {
  revision += 1
  listeners.forEach((fn) => fn())
}

/** useSyncExternalStore 的快照：订阅本 store 的任一次变化 */
export function getProjectStoreRevision(): number {
  return revision
}

export function setCurrentProject(id: string | null) {
  if (id) {
    localStorage.setItem(KEY, id)
  } else {
    localStorage.removeItem(KEY)
  }
  currentId = id
  notify()
}

export function getCurrentProjectId(): string | null {
  return currentId
}

/** ProjectSwitcher 加载完项目列表后调用，让 Gate 可以安全地渲染空态 */
export function setProjectsLoaded(loaded: boolean) {
  projectsLoaded = loaded
  notify()
}

export function getProjectsLoaded(): boolean {
  return projectsLoaded
}

export function subscribeCurrentProject(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

// 多标签页同步：A 标签切换项目，B 标签的切换器/菜单高亮跟随
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === KEY) {
      currentId = e.newValue
      notify()
    }
  })
}
