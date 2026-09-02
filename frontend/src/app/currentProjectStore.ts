// 当前项目上下文：工作台以某个具体项目为主线，projectId 不再只靠 URL 传递。
// 模块级轻量 store（仿 authStore.ts），localStorage 持久化 + storage 事件多标签页同步。
// 只存 id，项目对象由 useProject(id) 按需加载，避免 localStorage 副本陈旧。
const KEY = 'devmind.currentProjectId'

let currentId: string | null = localStorage.getItem(KEY)
// 项目列表是否已加载过（由 ProjectSwitcher 上报），供 ProjectContextGate 区分「加载中」与「真无项目」
let projectsLoaded = false
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach((fn) => fn())
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
