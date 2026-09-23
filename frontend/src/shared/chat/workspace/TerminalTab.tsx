// CAP-58/59 远程终端 tab：单条命令执行（POST {apiBase}/{id}/terminal/exec → runner 持久 shell 跑）。
// CAP-59 增强：Tab 补全（terminal/complete，老 runner 409 回落本地历史补全）、
// Ctrl+C 取消执行中命令（terminal/cancel，exit 130 已取消）/空闲清行、Ctrl+L 清屏。
// runner 侧持久 shell 持有 cwd/env（跨命令保持），ack 带回新 cwd 同步提示符；
// 白名单在 runner 侧强制（缺省只读档：ls/cd/cat/git 只读等）。
import { useEffect, useRef, useState } from 'react'
import { Button, Input, Spin, Tag, Typography } from 'antd'
import type { InputRef } from 'antd'
import type { ChatApiBase } from '../types'
import { terminalCancel, terminalComplete, terminalExec } from './api'
import { renderAnsi } from './AnsiText'

/** CAP-60 编程等宽字体栈（本机没装自动回落 Consolas，不打 webfont） */
const FONT_STACK = "'JetBrains Mono', 'Cascadia Code', 'Cascadia Mono', Consolas, Menlo, 'Courier New', monospace"
const FONT_SIZE_KEY = 'devmind.terminal.fontSize'
const FONT_SIZE_MIN = 10
const FONT_SIZE_MAX = 20

function loadFontSize(): number {
  const n = Number(localStorage.getItem(FONT_SIZE_KEY))
  return Number.isFinite(n) && n >= FONT_SIZE_MIN && n <= FONT_SIZE_MAX ? n : 12
}

interface TermEntry {
  id: number
  /** 执行时的 cwd（提示符回显用） */
  cwd: string
  command: string
  stdout?: string
  stderr?: string
  exitCode?: number
  timedOut?: boolean
  cancelled?: boolean
  /** HTTP 层失败（409 白名单/版本过低/超时等）整行标红 */
  error?: string
}

/** 候选列表公共前缀（多候选先补到公共前缀再出弹层，同 Xshell Tab 手感） */
function commonPrefix(list: string[]): string {
  if (list.length === 0) return ''
  let p = list[0]
  for (const s of list) {
    while (!s.startsWith(p)) p = p.slice(0, -1)
  }
  return p
}

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
  const [entries, setEntries] = useState<TermEntry[]>([])
  const [cwd, setCwd] = useState('')
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [fontSize, setFontSize] = useState(loadFontSize)
  const [cands, setCands] = useState<string[]>([])
  const [candIndex, setCandIndex] = useState(0)
  const [hint, setHint] = useState('')
  const seqRef = useRef(0)
  const historyRef = useRef<string[]>([])
  const histRef = useRef(-1) // -1 = 未在翻历史
  const completingRef = useRef(false)
  /** 老 runner（补全 409）只提示一次，之后 Tab 静默走本地历史补全 */
  const legacyRef = useRef(false)
  const bodyRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<InputRef | null>(null)

  // 新条目/新输出回到底部
  useEffect(() => {
    const el = bodyRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [entries])

  // 自动聚焦：激活时/命令执行完（disabled 解禁）都把光标放回输入框
  useEffect(() => {
    if (active && !running) inputRef.current?.focus()
  }, [active, running])

  // 提示 3s 自动消
  useEffect(() => {
    if (!hint) return
    const t = setTimeout(() => setHint(''), 3000)
    return () => clearTimeout(t)
  }, [hint])

  const closeCands = () => setCands([])

  const adjustFontSize = (delta: number) => {
    setFontSize((cur) => {
      const next = Math.min(FONT_SIZE_MAX, Math.max(FONT_SIZE_MIN, cur + delta))
      localStorage.setItem(FONT_SIZE_KEY, String(next))
      return next
    })
  }

  /** 替换输入行尾部的 word 为 replacement（word 为空串 = 行尾追加） */
  const applyCandidate = (value: string, word: string) => {
    setInput((cur) => {
      const tail = word && cur.endsWith(word) ? cur.slice(0, cur.length - word.length) : cur
      return tail + value
    })
    closeCands()
  }

  const doComplete = async () => {
    if (running || completingRef.current) return
    completingRef.current = true
    try {
      const cur = input
      if (legacyRef.current) {
        completeFromHistory(cur)
        return
      }
      const r = await terminalComplete(apiBase, sessionId, cur, cwd)
      const list = r.candidates ?? []
      if (list.length === 0) {
        setHint('无候选')
        return
      }
      if (list.length === 1) {
        applyCandidate(list[0], r.word)
        return
      }
      const prefix = commonPrefix(list)
      if (prefix.length > r.word.length) {
        applyCandidate(prefix, r.word)
      }
      setCandIndex(0)
      setCands(list)
    } catch (e) {
      // 409 = 老 runner 不认识补全帧：回落本地历史补全并提示一次
      legacyRef.current = true
      setHint('runner 版本过低，补全已回落为本地历史（到节点页升级 runner 可用真补全）')
      completeFromHistory(input)
    } finally {
      completingRef.current = false
    }
  }

  /** 本地历史补全（老 runner 回落）：最近一条以当前输入开头的历史命令 */
  const completeFromHistory = (cur: string) => {
    if (!cur) return
    const hit = [...historyRef.current].reverse().find((h) => h.startsWith(cur) && h !== cur)
    if (hit) setInput(hit)
  }

  const submit = () => {
    const command = input.trim()
    if (!command || running) return
    closeCands()
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
              ? {
                  ...e,
                  stdout: r.stdout,
                  stderr: r.stderr,
                  exitCode: r.exitCode,
                  timedOut: r.timedOut,
                  cancelled: r.cancelled,
                }
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
    // 候选弹层打开时：↑↓ 移动、Tab/Enter 选中、Esc 关闭（优先级最高）
    if (cands.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setCandIndex((i) => (i + 1) % cands.length)
        return
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault()
        setCandIndex((i) => (i - 1 + cands.length) % cands.length)
        return
      }
      if (e.key === 'Tab' || e.key === 'Enter') {
        e.preventDefault()
        applyCandidate(cands[candIndex], lastWordOf(input))
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        closeCands()
        return
      }
    }
    if (e.key === 'Tab') {
      e.preventDefault()
      void doComplete()
      return
    }
    if (e.key === 'c' && e.ctrlKey) {
      e.preventDefault()
      if (running) {
        terminalCancel(apiBase, sessionId).catch(() => {})
      } else {
        setInput('')
        closeCands()
      }
      return
    }
    if (e.key === 'l' && e.ctrlKey) {
      e.preventDefault()
      setEntries([])
      closeCands()
      return
    }
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

  /** 输入行尾部词（弹层选中时定位替换区间；与服务端 parseCompletion 的行尾词口径一致） */
  const lastWordOf = (line: string): string => {
    if (!line || /\s$/.test(line)) return ''
    const seg = line.split(/\|\||&&|[|;]/).pop() ?? ''
    const tokens = seg.trim().split(/\s+/)
    return tokens[tokens.length - 1] ?? ''
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, position: 'relative' }}>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 2, marginBottom: 4 }}>
        <Button size="small" type="text" title="减小字号" onClick={() => adjustFontSize(-1)}>
          A−
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 11, minWidth: 22, textAlign: 'center' }}>
          {fontSize}
        </Typography.Text>
        <Button size="small" type="text" title="增大字号" onClick={() => adjustFontSize(1)}>
          A+
        </Button>
      </div>
      <div
        ref={bodyRef}
        onClick={() => inputRef.current?.focus()}
        style={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          background: '#0f1115',
          borderRadius: 6,
          padding: '8px 10px',
          fontFamily: FONT_STACK,
          fontSize,
          lineHeight: 1.5,
        }}
      >
        {entries.length === 0 && (
          <Typography.Text style={{ color: '#8c8c8c', fontSize }}>
            在 runner 节点的会话工作区执行命令（env/cd 跨命令保持；Tab 补全、Ctrl+C 取消/清行、Ctrl+L 清屏）
          </Typography.Text>
        )}
        {entries.map((e) => (
          <div key={e.id} style={{ marginBottom: 8 }}>
            <div style={{ color: '#95de64', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
              <span style={{ color: '#597ef7' }}>{e.cwd || '/'}</span> $ {e.command}
            </div>
            {e.stdout && (
              <pre style={{ margin: 0, color: '#d9d9d9', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {renderAnsi(e.stdout)}
              </pre>
            )}
            {(e.stderr || e.error) && (
              <pre style={{ margin: 0, color: '#ff7875', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {e.stderr ? renderAnsi(e.stderr) : null}
                {e.error}
              </pre>
            )}
            {e.cancelled && (
              <Tag color="orange" style={{ fontSize: 11 }}>
                已取消 (exit 130)
              </Tag>
            )}
            {!e.cancelled && e.exitCode !== undefined && e.exitCode !== 0 && (
              <Tag color="red" style={{ fontSize: 11 }}>
                exit {e.exitCode}
                {e.timedOut ? '（超时被终止）' : ''}
              </Tag>
            )}
            {e.exitCode === undefined && !e.error && <Spin size="small" />}
          </div>
        ))}
      </div>
      {cands.length > 0 && (
        <div
          style={{
            position: 'absolute',
            bottom: 36,
            left: 0,
            right: 0,
            maxHeight: 180,
            overflow: 'auto',
            background: '#1f232b',
            border: '1px solid #30363f',
            borderRadius: 6,
            padding: '4px 0',
            zIndex: 10,
            fontFamily: FONT_STACK,
            fontSize,
          }}
        >
          {cands.slice(0, 50).map((c, i) => (
            <div
              key={c}
              onMouseDown={(e) => {
                e.preventDefault() // 抢焦点前选中
                applyCandidate(c, lastWordOf(input))
              }}
              onMouseEnter={() => setCandIndex(i)}
              style={{
                padding: '2px 10px',
                cursor: 'pointer',
                color: c.endsWith('/') ? '#95de64' : '#d9d9d9',
                background: i === candIndex ? '#30405f' : 'transparent',
              }}
            >
              {c}
            </div>
          ))}
          {cands.length > 50 && (
            <div style={{ padding: '2px 10px', color: '#8c8c8c' }}>… 共 {cands.length} 个候选</div>
          )}
        </div>
      )}
      <Input
        ref={inputRef}
        style={{ marginTop: 8, flexShrink: 0, fontFamily: FONT_STACK, fontSize }}
        size="small"
        prefix={<Typography.Text style={{ color: '#597ef7', fontSize: 12 }}>{cwd || '/'}</Typography.Text>}
        placeholder="$ 输入命令，Enter 执行（Tab 补全 · ↑↓ 历史 · Ctrl+C 取消 · Ctrl+L 清屏）"
        value={input}
        onChange={(e) => {
          setInput(e.target.value)
          closeCands()
        }}
        onPressEnter={submit}
        onKeyDown={onKeyDown}
        disabled={running}
        suffix={
          hint ? (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {hint}
            </Typography.Text>
          ) : null
        }
      />
    </div>
  )
}
