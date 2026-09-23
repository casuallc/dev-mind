// CAP-58 远程终端 tab：单条命令执行（POST {apiBase}/{id}/terminal/exec → runner 在会话代码目录跑）。
// cwd 由本组件持有（初始空 = 代码目录根），随每条命令下发，ack 带回新 cwd 更新提示符；
// 服务端无状态透传，白名单在 runner 侧强制（缺省只读档：ls/cd/cat/git 只读等）。
import { useEffect, useRef, useState } from 'react'
import { Input, Spin, Tag, Typography } from 'antd'
import type { ChatApiBase } from '../types'
import { terminalExec } from './api'

interface TermEntry {
  id: number
  /** 执行时的 cwd（提示符回显用） */
  cwd: string
  command: string
  stdout?: string
  stderr?: string
  exitCode?: number
  timedOut?: boolean
  /** HTTP 层失败（409 白名单/版本过低/超时等）整行标红 */
  error?: string
}

export default function TerminalTab({ apiBase, sessionId }: { apiBase: ChatApiBase; sessionId: string }) {
  const [entries, setEntries] = useState<TermEntry[]>([])
  const [cwd, setCwd] = useState('')
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const seqRef = useRef(0)
  const historyRef = useRef<string[]>([])
  const histRef = useRef(-1) // -1 = 未在翻历史
  const bodyRef = useRef<HTMLDivElement | null>(null)

  // 新条目/新输出回到底部
  useEffect(() => {
    const el = bodyRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [entries])

  const submit = () => {
    const command = input.trim()
    if (!command || running) return
    historyRef.current.push(command)
    histRef.current = -1
    setInput('')
    const id = ++seqRef.current
    const cmdCwd = cwd
    setEntries((es) => [...es, { id, cwd: cmdCwd, command }])
    setRunning(true)
    terminalExec(apiBase, sessionId, command, cmdCwd)
      .then((r) => {
        setEntries((es) =>
          es.map((e) =>
            e.id === id
              ? { ...e, stdout: r.stdout, stderr: r.stderr, exitCode: r.exitCode, timedOut: r.timedOut }
              : e,
          ),
        )
        if (r.cwd != null) setCwd(r.cwd)
      })
      .catch((e) => {
        setEntries((es) => es.map((x) => (x.id === id ? { ...x, error: (e as Error).message } : x)))
      })
      .finally(() => setRunning(false))
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    const hist = historyRef.current
    if (e.key === 'ArrowUp' && hist.length > 0) {
      e.preventDefault()
      histRef.current = histRef.current < 0 ? hist.length - 1 : Math.max(0, histRef.current - 1)
      setInput(hist[histRef.current] ?? '')
    } else if (e.key === 'ArrowDown' && histRef.current >= 0) {
      e.preventDefault()
      histRef.current += 1
      if (histRef.current >= hist.length) {
        histRef.current = -1
        setInput('')
      } else {
        setInput(hist[histRef.current])
      }
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div
        ref={bodyRef}
        style={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          background: '#0f1115',
          borderRadius: 6,
          padding: '8px 10px',
          fontFamily: 'Consolas, Menlo, monospace',
          fontSize: 12,
        }}
      >
        {entries.length === 0 && (
          <Typography.Text style={{ color: '#8c8c8c', fontSize: 12 }}>
            在 runner 节点的会话工作区执行单条命令（ls / cd / cat / git 等，runner 侧白名单缺省只读档）
          </Typography.Text>
        )}
        {entries.map((e) => (
          <div key={e.id} style={{ marginBottom: 8 }}>
            <div style={{ color: '#95de64', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
              <span style={{ color: '#597ef7' }}>{e.cwd || '/'}</span> $ {e.command}
            </div>
            {e.stdout && (
              <pre style={{ margin: 0, color: '#d9d9d9', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {e.stdout}
              </pre>
            )}
            {(e.stderr || e.error) && (
              <pre style={{ margin: 0, color: '#ff7875', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {e.stderr}
                {e.error}
              </pre>
            )}
            {e.exitCode !== undefined && e.exitCode !== 0 && (
              <Tag color="red" style={{ fontSize: 11 }}>
                exit {e.exitCode}
                {e.timedOut ? '（超时被终止）' : ''}
              </Tag>
            )}
            {e.exitCode === undefined && !e.error && <Spin size="small" />}
          </div>
        ))}
      </div>
      <Input
        style={{ marginTop: 8, flexShrink: 0, fontFamily: 'Consolas, Menlo, monospace' }}
        size="small"
        prefix={<Typography.Text style={{ color: '#597ef7', fontSize: 12 }}>{cwd || '/'}</Typography.Text>}
        placeholder="$ 输入命令，Enter 执行（↑↓ 翻历史）"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onPressEnter={submit}
        onKeyDown={onKeyDown}
        disabled={running}
      />
    </div>
  )
}
