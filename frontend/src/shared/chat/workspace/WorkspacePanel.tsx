// CAP-54 会话工作区实时视图：对话面板右侧可折叠栏（变更 / 文件 两个 tab）。
// 变更 = WS 旁路推送的 git 快照（首次打开或终态无推送时 REST 兜底拉一次）；
// 文件 = 懒加载目录树 + 文件内容抽屉；diff 仅项目会话（问答沙箱非 git）。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Badge, Button, Drawer, Empty, Spin, Tabs, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import {
  FileTextOutlined,
  FolderOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import type { ChatApiBase, WorkspaceChange, WorkspaceSnapshot } from '../types'
import {
  fetchWorkspaceDiff,
  fetchWorkspaceFile,
  fetchWorkspaceStatus,
  fetchWorkspaceTree,
} from './api'
import { showError } from '../../utils/showError'
import { fmtTime } from '../../utils/format'

const WIDTH = 360

/** porcelain XY → 人类可读状态（取变化更显著的一侧：未跟踪 > 删除 > 新增 > 改名 > 修改） */
function statusMeta(c: WorkspaceChange): { label: string; color: string } {
  const xy = c.code
  if (xy.includes('?')) return { label: '新增', color: 'blue' }
  if (xy.includes('D')) return { label: '删除', color: 'red' }
  if (xy.includes('A')) return { label: '新增', color: 'green' }
  if (xy.includes('R')) return { label: '改名', color: 'cyan' }
  return { label: '修改', color: 'gold' }
}

/** diff 文本按行上色（+ 绿 / - 红 / @@ 蓝），不引语法高亮依赖 */
function DiffView({ diff }: { diff: string }) {
  return (
    <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
      {diff.split('\n').map((line, i) => {
        let color: string | undefined
        if (line.startsWith('+') && !line.startsWith('+++')) color = '#237804'
        else if (line.startsWith('-') && !line.startsWith('---')) color = '#cf1322'
        else if (line.startsWith('@@')) color = '#0958d9'
        return (
          <div key={i} style={color ? { color } : undefined}>
            {line || ' '}
          </div>
        )
      })}
    </pre>
  )
}

export default function WorkspacePanel({
  apiBase,
  sessionId,
  /** WS 旁路推来的最新快照（undefined = 还没推过） */
  snapshot,
  /** 项目会话才支持单文件 diff（/chats 无此端点） */
  canDiff,
}: {
  apiBase: ChatApiBase
  sessionId: string
  snapshot?: WorkspaceSnapshot
  canDiff: boolean
}) {
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState<'changes' | 'files'>('changes')
  // WS 还没推过（终态会话/刚打开）时 REST 兜底拉一次；推送到达后以推送为准
  const [pulled, setPulled] = useState<WorkspaceSnapshot | null>(null)
  const [loadingStatus, setLoadingStatus] = useState(false)
  const snap = snapshot ?? pulled ?? undefined

  const pullStatus = useCallback(() => {
    setLoadingStatus(true)
    fetchWorkspaceStatus(apiBase, sessionId)
      .then(setPulled)
      .catch((e) => showError(e, '加载工作区状态失败'))
      .finally(() => setLoadingStatus(false))
  }, [apiBase, sessionId])

  useEffect(() => {
    if (open && !snapshot) pullStatus()
    // snapshot 对象随每次推送变引用，只以"是否出现过"为闸
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, sessionId, snapshot === undefined])

  // ---------------- 变更 tab ----------------
  const [diffOpen, setDiffOpen] = useState(false)
  const [diffTitle, setDiffTitle] = useState('')
  const [diffBody, setDiffBody] = useState<React.ReactNode>(null)
  const [diffLoading, setDiffLoading] = useState(false)

  const openChange = useCallback(
    (repo: string, c: WorkspaceChange) => {
      setDiffTitle(c.path)
      setDiffOpen(true)
      setDiffLoading(true)
      setDiffBody(null)
      const showFile = () =>
        fetchWorkspaceFile(apiBase, sessionId, repo ? `${repo}/${c.path}` : c.path)
          .then((f) =>
            setDiffBody(
              <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {f.content}
              </pre>,
            ),
          )
      // 问答无 diff 端点、删除的文件无内容可看、未跟踪文件没有 diff 基线 → 直接看文件内容
      const deleted = c.code.includes('D')
      if (!canDiff || deleted) {
        if (deleted) setDiffBody(<Empty description="文件已删除，无内容可看" />)
        else showFile().catch((e) => showError(e, '加载文件失败'))
        setDiffLoading(false)
        return
      }
      fetchWorkspaceDiff(apiBase, sessionId, c.path, repo || undefined)
        .then((d) => {
          if (d.untracked || !d.diff) return showFile()
          setDiffBody(<DiffView diff={d.diff} />)
        })
        .catch((e) => showError(e, '加载 diff 失败'))
        .finally(() => setDiffLoading(false))
    },
    [apiBase, sessionId, canDiff],
  )

  const changesTab = useMemo(() => {
    if (loadingStatus && !snap) return <Spin style={{ display: 'block', margin: '48px auto' }} />
    if (!snap) return <Empty description="暂无工作区数据（老版本 runner 不支持实时推送）" />
    if (!snap.gitAvailable) return <Empty description="沙箱目录不是 git 仓库，仅支持文件浏览" />
    const totalChanges = snap.repos.reduce((n, r) => n + r.changes.length, 0)
    if (totalChanges === 0) return <Empty description="工作区干净，暂无未提交变更" />
    return (
      <div>
        {snap.repos.map((r) => (
          <div key={r.name} style={{ marginBottom: 12 }}>
            {snap.repos.length > 1 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                <FolderOutlined /> {r.name || '.'}
              </Typography.Text>
            )}
            {r.branch && (
              <Tag style={{ marginLeft: 6, fontSize: 11 }} color="default">
                {r.branch}
              </Tag>
            )}
            {r.error && (
              <Typography.Text type="warning" style={{ display: 'block', fontSize: 12, marginTop: 4 }}>
                采集失败：{r.error}
              </Typography.Text>
            )}
            <div style={{ marginTop: 4 }}>
              {r.changes.map((c) => {
                const meta = statusMeta(c)
                return (
                  <div
                    key={c.path}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 6,
                      padding: '3px 4px',
                      cursor: 'pointer',
                      borderRadius: 4,
                      fontSize: 12,
                    }}
                    onClick={() => openChange(r.name, c)}
                  >
                    <Tag color={meta.color} style={{ marginInlineEnd: 0, fontSize: 11 }}>
                      {meta.label}
                    </Tag>
                    <Typography.Text
                      style={{ flex: 1, fontSize: 12 }}
                      ellipsis={{ tooltip: c.path }}
                    >
                      {c.path}
                    </Typography.Text>
                    {(c.adds !== undefined || c.dels !== undefined) && (
                      <Typography.Text type="secondary" style={{ fontSize: 11, flexShrink: 0 }}>
                        <span style={{ color: '#237804' }}>+{c.adds ?? 0}</span>{' '}
                        <span style={{ color: '#cf1322' }}>−{c.dels ?? 0}</span>
                      </Typography.Text>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        ))}
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {snap.ts ? `更新于 ${fmtTime(new Date(snap.ts).toISOString())}` : ''}
          {snap.total ? ` · ${snap.total.files} 个文件 +${snap.total.adds} −${snap.total.dels}` : ''}
        </Typography.Text>
      </div>
    )
  }, [snap, loadingStatus, openChange])

  // ---------------- 文件 tab ----------------
  const [treeData, setTreeData] = useState<DataNode[]>([])
  const [treeLoaded, setTreeLoaded] = useState(false)
  const [fileOpen, setFileOpen] = useState(false)
  const [fileTitle, setFileTitle] = useState('')
  const [fileBody, setFileBody] = useState<React.ReactNode>(null)

  const toNode = useCallback(
    (e: { name: string; path: string; dir: boolean }): DataNode => ({
      key: e.path,
      title: e.name,
      isLeaf: !e.dir,
      icon: e.dir ? <FolderOutlined /> : <FileTextOutlined />,
    }),
    [],
  )

  const loadTree = useCallback(
    (path?: string) =>
      fetchWorkspaceTree(apiBase, sessionId, path).then((r) =>
        r.entries.map((e) => toNode(e)),
      ),
    [apiBase, sessionId, toNode],
  )

  useEffect(() => {
    if (!open || tab !== 'files' || treeLoaded) return
    loadTree()
      .then(setTreeData)
      .then(() => setTreeLoaded(true))
      .catch((e) => showError(e, '加载目录失败'))
  }, [open, tab, treeLoaded, loadTree])

  const onLoadData = useCallback(
    (node: DataNode): Promise<void> =>
      loadTree(String(node.key)).then((children) => {
        setTreeData((prev) => {
          const patch = (list: DataNode[]): DataNode[] =>
            list.map((n) =>
              n.key === node.key ? { ...n, children } : { ...n, children: n.children ? patch(n.children) : n.children },
            )
          return patch(prev)
        })
      }),
    [loadTree],
  )

  const onSelectFile = useCallback(
    (keys: React.Key[], info: { node: DataNode }) => {
      if (keys.length === 0 || !info.node.isLeaf) return
      const path = String(keys[0])
      setFileTitle(path)
      setFileOpen(true)
      setFileBody(<Spin style={{ display: 'block', margin: '48px auto' }} />)
      fetchWorkspaceFile(apiBase, sessionId, path)
        .then((f) =>
          setFileBody(
            <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
              {f.content}
            </pre>,
          ),
        )
        .catch((e) => {
          setFileBody(null)
          setFileOpen(false)
          showError(e, '加载文件失败')
        })
    },
    [apiBase, sessionId],
  )

  // 折叠态：窄条 + 展开按钮（变更数徽标提示有新东西可看）
  if (!open) {
    const n = snap?.repos.reduce((acc, r) => acc + r.changes.length, 0) ?? 0
    return (
      <div
        style={{
          width: 36,
          flexShrink: 0,
          borderLeft: '1px solid #f0f0f0',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          paddingTop: 8,
        }}
      >
        <Badge count={n} size="small" offset={[-4, 4]}>
          <Button type="text" size="small" icon={<MenuUnfoldOutlined />} onClick={() => setOpen(true)} title="工作区" />
        </Badge>
      </div>
    )
  }

  return (
    <div
      style={{
        width: WIDTH,
        flexShrink: 0,
        borderLeft: '1px solid #f0f0f0',
        paddingLeft: 12,
        marginLeft: 12,
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8, flexShrink: 0 }}>
        <Typography.Text strong style={{ flex: 1 }}>
          工作区
        </Typography.Text>
        <Button type="text" size="small" icon={<ReloadOutlined />} onClick={pullStatus} title="刷新状态" />
        <Button type="text" size="small" icon={<MenuFoldOutlined />} onClick={() => setOpen(false)} title="收起" />
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <Tabs
          size="small"
          activeKey={tab}
          onChange={(k) => setTab(k as 'changes' | 'files')}
          items={[
            {
              key: 'changes',
              label: '变更',
              children: changesTab,
            },
            {
              key: 'files',
              label: '文件',
              children: (
                <Tree
                  showIcon
                  blockNode
                  treeData={treeData}
                  loadData={onLoadData}
                  onSelect={onSelectFile}
                  selectedKeys={[]}
                />
              ),
            },
          ]}
        />
      </div>

      <Drawer title={diffTitle} open={diffOpen} onClose={() => setDiffOpen(false)} width={720}>
        {diffLoading ? <Spin /> : diffBody}
      </Drawer>
      <Drawer title={fileTitle} open={fileOpen} onClose={() => setFileOpen(false)} width={720}>
        {fileBody}
      </Drawer>
    </div>
  )
}
