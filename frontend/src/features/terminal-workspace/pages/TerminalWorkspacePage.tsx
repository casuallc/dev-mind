// CAP-115 终端工作区详情页：左文件树 + 中文本编辑器 + 右终端（TerminalView 渲染核）。
// 打开终端 = 占用锁 + 重发 terminal_ws_prepare 重绑 + 会话 RUNNING；30s 心跳续期，
// 90s 无心跳/节点离线被巡检挂起（回到本页重新「打开终端」即恢复）。
// 危险命令 exec 遇 428 由 TerminalView（dangerConfirm）弹确认框重发；
// 文件保存带 expectedSha 乐观锁，409 冲突提示刷新。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Tag,
  Tooltip,
  Tree,
  Typography,
  message,
} from 'antd'
import type { TreeDataNode } from 'antd'
import {
  ArrowLeftOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
  StopOutlined,
} from '@ant-design/icons'
import TerminalView, { type TerminalHandlers } from '../../../shared/terminal/TerminalView'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { setCurrentProject } from '../../../app/currentProjectStore'
import { usePermissions } from '../../auth/usePermissions'
import FinalizeWorkspaceModal from '../components/FinalizeWorkspaceModal'
import ReleaseWorkspaceModal from '../components/ReleaseWorkspaceModal'
import { showFinalizeDetail } from '../components/finalizeDetail'
import * as tw from '../api'
import type { TerminalSessionView, TerminalWorkspaceView } from '../types'

const STATE_TAG: Record<string, { color: string; label: string }> = {
  OPEN: { color: 'blue', label: '使用中' },
  FINALIZED: { color: 'green', label: '已收口' },
  RELEASED: { color: 'default', label: '已释放' },
}

const HEARTBEAT_MS = 30_000

/** UTF-8 安全 base64（分块防栈溢出；服务端单文件上限 256KB） */
function toBase64(s: string): string {
  const bytes = new TextEncoder().encode(s)
  let bin = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(bin)
}

interface EditingFile {
  path: string
  content: string
  /** 加载时的内容 sha（保存时作 expectedSha 回传） */
  sha?: string
  dirty: boolean
  saving: boolean
}

export default function TerminalWorkspacePage() {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { canAction } = usePermissions()
  const canManage = canAction('project.terminal.action.manage')
  const canFinalize = canAction('project.terminal.action.finalize')

  const [ws, setWs] = useState<TerminalWorkspaceView | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [session, setSession] = useState<TerminalSessionView | null>(null)
  const [opening, setOpening] = useState(false)
  /** 打开 409（他人持锁）的占用方提示 */
  const [openError, setOpenError] = useState('')
  const [tree, setTree] = useState<TreeDataNode[]>([])
  const [treeLoaded, setTreeLoaded] = useState(false)
  const [editing, setEditing] = useState<EditingFile | null>(null)
  const [fileLoading, setFileLoading] = useState(false)
  const [finalizeOpen, setFinalizeOpen] = useState(false)
  const [releaseOpen, setReleaseOpen] = useState(false)
  const heartbeatRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const running = session?.state === 'RUNNING'

  const load = useCallback(async () => {
    try {
      const w = await tw.getTerminalWorkspace(id)
      setWs(w)
      setCurrentProject(w.projectId)
    } catch (e) {
      showError(e, '终端工作区加载失败')
      setNotFound(true)
    }
  }, [id])

  useEffect(() => {
    void load()
  }, [load])

  // ---------------- 心跳：RUNNING 期间 30s 一跳，失败（挂起/锁丢）即停 ----------------

  useEffect(() => {
    if (!running) {
      if (heartbeatRef.current) clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
      return
    }
    heartbeatRef.current = setInterval(() => {
      tw.terminalHeartbeat(id).catch(() => {
        // 409 = 已被巡检挂起/锁被抢占：停跳并提示重新打开
        setSession((s) => (s ? { ...s, state: 'SUSPENDED' } : s))
        setOpenError('终端会话已被挂起（心跳中断或锁被解除），请重新打开终端。')
      })
    }, HEARTBEAT_MS)
    return () => {
      if (heartbeatRef.current) clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
    }
  }, [running, id])

  // ---------------- 终端通道 ----------------

  const handlers = useMemo<TerminalHandlers>(
    () => ({
      exec: (command, cwd, confirmed) => tw.terminalExec(id, command, cwd, confirmed),
      complete: (input, cwd) => tw.terminalComplete(id, input, cwd),
      cancel: () => tw.terminalCancel(id),
    }),
    [id],
  )

  const onOpen = async () => {
    setOpening(true)
    setOpenError('')
    try {
      const v = await tw.openTerminal(id)
      setSession(v.session)
      message.success(`终端已打开（${v.branch}）`)
      void load()
    } catch (e) {
      // 409 = 他人持锁（服务端话术带占用方）
      setOpenError((e as Error).message)
    } finally {
      setOpening(false)
    }
  }

  const onClose = async () => {
    try {
      const s = await tw.terminalClose(id)
      setSession(s)
      message.success('终端已关闭')
      void load()
    } catch (e) {
      showError(e, '关闭终端失败')
    }
  }

  /** M3 收口记录查看（detail 空 = 未收口过） */
  const onShowFinalizeDetail = async () => {
    try {
      const r = await tw.fetchFinalizeDetail(id)
      if (r.detail) {
        showFinalizeDetail(r.detail, r.finalizedAt)
      } else {
        message.info('该工作区无收口记录')
      }
    } catch (e) {
      showError(e, '收口记录加载失败')
    }
  }

  // ---------------- 文件树 / 编辑器 ----------------

  const toNode = useCallback(
    (entries: { name: string; path: string; dir: boolean }[]): TreeDataNode[] =>
      entries
        .filter((e) => e.name !== '.git')
        .map((e) => ({
          key: e.path,
          title: e.name,
          isLeaf: !e.dir,
        })),
    [],
  )

  const loadRoot = useCallback(async () => {
    try {
      const r = await tw.fetchFileTree(id)
      setTree(toNode(r.entries))
      setTreeLoaded(true)
    } catch (e) {
      showError(e, '文件树加载失败')
    }
  }, [id, toNode])

  useEffect(() => {
    if (ws && !treeLoaded) void loadRoot()
  }, [ws, treeLoaded, loadRoot])

  const onLoadData = (node: TreeDataNode): Promise<void> =>
    tw
      .fetchFileTree(id, String(node.key))
      .then((r) => {
        setTree((cur) => updateChildren(cur, node.key, toNode(r.entries)))
      })
      .catch((e) => showError(e, '目录加载失败'))

  const onSelectFile = async (path: string) => {
    setFileLoading(true)
    try {
      const r = await tw.fetchFileContent(id, path)
      setEditing({ path, content: r.content, sha: (r as { sha?: string }).sha, dirty: false, saving: false })
    } catch (e) {
      showError(e, '文件读取失败')
    } finally {
      setFileLoading(false)
    }
  }

  const onSave = async () => {
    if (!editing) return
    setEditing({ ...editing, saving: true })
    try {
      const r = await tw.writeFile(id, editing.path, toBase64(editing.content), editing.sha ?? null)
      setEditing({ ...editing, sha: r.sha, dirty: false, saving: false })
      message.success(`已保存 ${editing.path}`)
    } catch (e) {
      setEditing({ ...editing, saving: false })
      const err = e as { status?: number; message?: string }
      if (err.status === 409) {
        // expectedSha 乐观锁冲突：提示刷新重载
        Modal.confirm({
          centered: true,
          title: '保存冲突',
          content: `${err.message ?? '文件已被修改'}。重新加载将丢弃当前编辑内容，是否刷新？`,
          okText: '重新加载',
          cancelText: '继续编辑',
          onOk: () => void onSelectFile(editing.path),
        })
      } else {
        showError(e, '保存失败')
      }
    }
  }

  // ---------------- 渲染 ----------------

  if (notFound) {
    return (
      <Card style={pageCardStyle}>
        <Empty description="终端工作区不存在或已删除" />
      </Card>
    )
  }
  if (!ws) {
    return (
      <Card style={pageCardStyle}>
        <Spin />
      </Card>
    )
  }

  const stateTag = STATE_TAG[ws.state] ?? { color: 'default', label: ws.state }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={8}>
          <Typography.Text code>{ws.workspaceKey}</Typography.Text>
          <Tag color={stateTag.color} style={{ marginInlineEnd: 0 }}>{stateTag.label}</Tag>
          {ws.lockOwner && (
            <Tooltip title={`${ws.lockOwner} 于 ${fmtTime(ws.lockAt ?? undefined)} 加锁`}>
              <Tag color="orange" style={{ marginInlineEnd: 0 }}>占用：{ws.lockOwner}</Tag>
            </Tooltip>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            分支 {ws.branch} · 节点 {ws.agentNodeId} · 归属 {ws.owner}
          </Typography.Text>
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {ws.state === 'OPEN' && !running && (
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={opening}
              disabled={!canManage}
              title={canManage ? undefined : '无终端工作区管理权限'}
              onClick={() => void onOpen()}
            >
              打开终端
            </Button>
          )}
          {running && (
            <Button danger icon={<StopOutlined />} onClick={() => void onClose()}>
              关闭终端
            </Button>
          )}
          {ws.finalizedAt && (
            <Button onClick={() => void onShowFinalizeDetail()}>收口记录</Button>
          )}
          {ws.state === 'OPEN' && canFinalize && !running && (
            <Button type="primary" ghost onClick={() => setFinalizeOpen(true)}>
              收口
            </Button>
          )}
          {ws.state !== 'RELEASED' && canFinalize && !running && (
            <Button danger onClick={() => setReleaseOpen(true)}>
              释放
            </Button>
          )}
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/terminal-workspaces')}>
            返回列表
          </Button>
        </Space>
      }
    >
      <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 12 }}>
        {/* 文件树 */}
        <div
          style={{
            width: 260,
            flexShrink: 0,
            overflow: 'auto',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            padding: 8,
          }}
        >
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            文件（浏览无需打开终端，保存需打开）
          </Typography.Text>
          <Tree
            style={{ marginTop: 4 }}
            loadData={onLoadData}
            treeData={tree}
            onSelect={(keys, info) => {
              if (info.node.isLeaf && keys.length > 0) void onSelectFile(String(keys[0]))
            }}
          />
        </div>
        {/* 简易编辑器 */}
        <div
          style={{
            flex: 1,
            minWidth: 0,
            display: 'flex',
            flexDirection: 'column',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            padding: 8,
          }}
        >
          {editing ? (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                <Typography.Text code style={{ fontSize: 12 }}>{editing.path}</Typography.Text>
                <Space size={8}>
                  {editing.dirty && <Tag color="orange" style={{ marginInlineEnd: 0 }}>未保存</Tag>}
                  <Button
                    size="small"
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={editing.saving}
                    disabled={!editing.dirty || !running}
                    title={running ? undefined : '保存需先打开终端（写文件要求持有占用锁）'}
                    onClick={() => void onSave()}
                  >
                    保存
                  </Button>
                </Space>
              </div>
              <Input.TextArea
                style={{
                  flex: 1,
                  minHeight: 0,
                  resize: 'none',
                  fontFamily:
                    "'JetBrains Mono', 'Cascadia Code', 'Cascadia Mono', Consolas, Menlo, 'Courier New', monospace",
                  fontSize: 12,
                }}
                value={editing.content}
                onChange={(e) => setEditing({ ...editing, content: e.target.value, dirty: true })}
              />
            </>
          ) : (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {fileLoading ? <Spin /> : <Typography.Text type="secondary">从左侧文件树选择文件查看/编辑</Typography.Text>}
            </div>
          )}
        </div>
        {/* 终端 */}
        <div
          style={{
            width: '44%',
            flexShrink: 0,
            display: 'flex',
            flexDirection: 'column',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            padding: 8,
          }}
        >
          {running ? (
            <TerminalView
              handlers={handlers}
              dangerConfirm
              emptyHint="终端已就绪（写档白名单：git add/commit/push、文件写命令、构建命令；危险命令需二次确认）"
            />
          ) : (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12 }}>
              {openError && (
                <Alert type="warning" showIcon message="无法打开终端" description={openError} style={{ width: '100%' }} />
              )}
              {ws.state === 'OPEN' ? (
                <>
                  <Typography.Text type="secondary">
                    {session?.state === 'SUSPENDED'
                      ? '会话已挂起（90s 无心跳或节点离线），重新打开即恢复。'
                      : '打开终端后将占用该工作区（他人打开会被拒），可直接执行 bash 命令。'}
                  </Typography.Text>
                  <Button
                    type="primary"
                    icon={<PlayCircleOutlined />}
                    loading={opening}
                    disabled={!canManage}
                    onClick={() => void onOpen()}
                  >
                    打开终端
                  </Button>
                </>
              ) : (
                <Typography.Text type="secondary">
                  工作区{stateTag.label}，不可再打开终端。
                </Typography.Text>
              )}
            </div>
          )}
        </div>
      </div>

      {/* M3 收口 / 释放确认弹窗 */}
      <FinalizeWorkspaceModal
        ws={ws}
        open={finalizeOpen}
        onCancel={() => setFinalizeOpen(false)}
        onDone={() => {
          setFinalizeOpen(false)
          void load()
        }}
      />
      <ReleaseWorkspaceModal
        ws={ws}
        open={releaseOpen}
        onCancel={() => setReleaseOpen(false)}
        onDone={() => {
          setReleaseOpen(false)
          void load()
        }}
      />
    </Card>
  )
}

/** 树局部更新：把 children 挂到 key 命中的节点下 */
function updateChildren(nodes: TreeDataNode[], key: React.Key, children: TreeDataNode[]): TreeDataNode[] {
  return nodes.map((n) => {
    if (n.key === key) return { ...n, children }
    if (n.children) return { ...n, children: updateChildren(n.children, key, children) }
    return n
  })
}
