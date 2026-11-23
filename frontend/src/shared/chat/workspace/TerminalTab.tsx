// CAP-58/59 远程终端 tab：薄壳，渲染核已抽至 shared/terminal/TerminalView（CAP-115 M2）。
// 会话/问答场景不开 dangerConfirm（服务端该通道不判危险命令）。
import { useMemo } from 'react'
import TerminalView, { type TerminalHandlers } from '../../terminal/TerminalView'
import type { ChatApiBase } from '../types'
import { terminalCancel, terminalComplete, terminalExec } from './api'

export default function TerminalTab({
  apiBase,
  sessionId,
  /** 抽屉已展开且本 tab 在前台 = true；配合 running 回落把焦点钉在输入框（抽屉动画/命令执行都会抢焦点） */
  active = true,
}: {
  apiBase: ChatApiBase
  sessionId: string
  active?: boolean
}) {
  const handlers = useMemo<TerminalHandlers>(
    () => ({
      exec: (command, cwd) => terminalExec(apiBase, sessionId, command, cwd),
      complete: (input, cwd) => terminalComplete(apiBase, sessionId, input, cwd),
      cancel: () => terminalCancel(apiBase, sessionId),
    }),
    [apiBase, sessionId],
  )
  return <TerminalView handlers={handlers} active={active} />
}
