// CAP-54 工作区视图 REST：快照优先走 WS 旁路推送，这里兜底拉取（首次打开/终态浏览）+ 按需拉树/文件/diff。
// tree/file/diff 都是拉模型（数据无界，不进 WS）；status 服务端缓存优先。
import { api } from '../../api/client'
import type { ChatApiBase, WorkspaceSnapshot } from '../types'

export interface WorkspaceTreeEntry {
  name: string
  /** 相对工作区根的路径（再传给 tree/file 下钻） */
  path: string
  dir: boolean
  size?: number
}

export interface WorkspaceTreeResult {
  entries: WorkspaceTreeEntry[]
  /** 单目录超 500 条被截断 */
  truncated?: boolean
}

export interface WorkspaceFileResult {
  content: string
  size: number
}

/** diff 响应：untracked=true 时 diff 为空串（新文件没有可 diff 的基线），前端退回文件内容查看 */
export interface WorkspaceDiffResult {
  untracked: boolean
  diff: string
}

function qs(params: Record<string, string | undefined>): string {
  const q = Object.entries(params)
    .filter((e): e is [string, string] => e[1] !== undefined && e[1] !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  return q ? `?${q}` : ''
}

export function fetchWorkspaceStatus(apiBase: ChatApiBase, id: string) {
  return api.get<WorkspaceSnapshot>(`${apiBase}/${id}/workspace/status`)
}

export function fetchWorkspaceTree(apiBase: ChatApiBase, id: string, path?: string) {
  return api.get<WorkspaceTreeResult>(`${apiBase}/${id}/workspace/tree${qs({ path })}`)
}

export function fetchWorkspaceFile(apiBase: ChatApiBase, id: string, path: string) {
  return api.get<WorkspaceFileResult>(`${apiBase}/${id}/workspace/file${qs({ path })}`)
}

/** 仅项目会话（/sessions）有 diff；问答沙箱非 git 无此端点 */
export function fetchWorkspaceDiff(apiBase: ChatApiBase, id: string, path: string, repo?: string) {
  return api.get<WorkspaceDiffResult>(`${apiBase}/${id}/workspace/diff${qs({ path, repo })}`)
}

// CAP-115 M2：终端结果类型上移到 shared/terminal/types.ts（TerminalView 渲染核共用），此处再导出保持旧路径
import type { TerminalCompleteResult, TerminalExecResult } from '../../terminal/types'
export type { TerminalCompleteResult, TerminalExecResult } from '../../terminal/types'

/** CAP-58：终端单条命令执行（cwd 前端持有随命令下发，空 = 代码目录根） */
export function terminalExec(apiBase: ChatApiBase, id: string, command: string, cwd: string) {
  return api.post<TerminalExecResult>(`${apiBase}/${id}/terminal/exec`, { command, cwd })
}

/** CAP-59：Tab 补全（老 runner v17- 走 409，前端回落本地历史补全） */
export function terminalComplete(apiBase: ChatApiBase, id: string, input: string, cwd: string) {
  return api.post<TerminalCompleteResult>(`${apiBase}/${id}/terminal/complete`, { input, cwd })
}

/** CAP-59：取消执行中的终端命令（无进行中命令 = no-op） */
export function terminalCancel(apiBase: ChatApiBase, id: string) {
  return api.post<void>(`${apiBase}/${id}/terminal/cancel`, {})
}
