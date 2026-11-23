// CAP-115 M2 终端通道共享契约：TerminalView 的入参/出参类型。
// shared/chat/workspace/api.ts 从此处再导出（保持既有 import 路径不破）。

/** 终端单条命令执行结果（白名单拒绝/危险命令确认等走 HTTP 非 2xx，不会出现在这里） */
export interface TerminalExecResult {
  ok: boolean
  exitCode: number
  stdout: string
  stderr: string
  /** 命令执行后的新 cwd（相对代码目录 POSIX 路径；越界 cd 被拒时维持原值） */
  cwd: string | null
  timedOut?: boolean
  /** 被 Ctrl+C 取消（exitCode=130） */
  cancelled?: boolean
  error?: string
}

/** Tab 补全结果 */
export interface TerminalCompleteResult {
  ok: boolean
  /** 被补全的词（输入行尾部等于 word 的片段，前端据此定位替换区间） */
  word: string
  /** 候选（目录带 / 后缀；上限 100） */
  candidates: string[]
  error?: string
}
