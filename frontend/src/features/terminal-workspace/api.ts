// CAP-115 终端工作区 REST：M1 登记/挂接/锁 + M2 终端会话通道与文件读写/命令历史。
import { api } from '../../shared/api/client'
import type {
  WorkspaceFileResult,
  WorkspaceTreeResult,
} from '../../shared/chat/workspace/api'
import type { TerminalCompleteResult, TerminalExecResult } from '../../shared/terminal/types'
import type {
  TerminalFileWriteResult,
  TerminalHistoryEntry,
  TerminalOpenView,
  TerminalSessionView,
  TerminalWorkspaceView,
} from './types'

const BASE = '/terminal-workspaces'

function qs(params: Record<string, string | undefined>): string {
  const q = Object.entries(params)
    .filter((e): e is [string, string] => e[1] !== undefined && e[1] !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  return q ? `?${q}` : ''
}

// ---------------- M1 登记 ----------------

export function listTerminalWorkspaces(projectId: string) {
  return api.get<TerminalWorkspaceView[]>(`${BASE}${qs({ projectId })}`)
}

export function getTerminalWorkspace(id: string) {
  return api.get<TerminalWorkspaceView>(`${BASE}/${id}`)
}

export function createTerminalWorkspace(req: {
  projectId: string
  requirementId?: string | null
  workItemId?: string | null
  baseBranch?: string | null
  agentNodeId?: string | null
}) {
  return api.post<TerminalWorkspaceView>(BASE, req)
}

export function attachTerminalWorkspace(req: {
  workspaceKey: string
  projectId?: string | null
  baseBranch?: string | null
  agentNodeId?: string | null
}) {
  return api.post<TerminalWorkspaceView>(`${BASE}/attach`, req)
}

export function lockTerminalWorkspace(id: string) {
  return api.post<TerminalWorkspaceView>(`${BASE}/${id}/lock`, {})
}

export function unlockTerminalWorkspace(id: string) {
  return api.post<TerminalWorkspaceView>(`${BASE}/${id}/unlock`, {})
}

// ---------------- M2 终端会话通道 ----------------

export function openTerminal(id: string) {
  return api.post<TerminalOpenView>(`${BASE}/${id}/terminal/open`, {})
}

/** exec 遇 428 NEED_CONFIRM 时由 TerminalView 弹确认框后以 confirmed=true 重发 */
export function terminalExec(id: string, command: string, cwd: string, confirmed?: boolean) {
  return api.post<TerminalExecResult>(`${BASE}/${id}/terminal/exec`, { command, cwd, confirmed })
}

export function terminalComplete(id: string, input: string, cwd: string) {
  return api.post<TerminalCompleteResult>(`${BASE}/${id}/terminal/complete`, { input, cwd })
}

export function terminalCancel(id: string) {
  return api.post<{ ok: boolean }>(`${BASE}/${id}/terminal/cancel`, {})
}

export function terminalHeartbeat(id: string) {
  return api.post<TerminalSessionView>(`${BASE}/${id}/terminal/heartbeat`, {})
}

export function terminalClose(id: string) {
  return api.post<TerminalSessionView>(`${BASE}/${id}/terminal/close`, {})
}

// ---------------- M2 文件读写 / 命令历史 ----------------

export function fetchFileTree(id: string, path?: string) {
  return api.get<WorkspaceTreeResult>(`${BASE}/${id}/files/tree${qs({ path })}`)
}

export function fetchFileContent(id: string, path: string) {
  return api.get<WorkspaceFileResult>(`${BASE}/${id}/files/file${qs({ path })}`)
}

export function writeFile(id: string, path: string, contentBase64: string, expectedSha?: string | null) {
  return api.put<TerminalFileWriteResult>(`${BASE}/${id}/files/file`, { path, contentBase64, expectedSha })
}

export function fetchHistory(id: string, limit = 50) {
  return api.get<TerminalHistoryEntry[]>(`${BASE}/${id}/history${qs({ limit: String(limit) })}`)
}
