// CAP-113 FR-03 文件查看抽屉（叠在提交日志抽屉之上的二级抽屉）：
// 点击变更文件打开；Segmented 切换「变更对比 / 查看原文」，各自懒加载、当次缓存。
// diff 视图为 GitHub 风格 unified diff 渲染（hunk 头 + 双列行号 + 增删行着色）；
// 原文视图等宽 + 行号；二进制/截断/已删除给 Alert 说明。
import { useEffect, useMemo, useState } from 'react'
import type { CSSProperties } from 'react'
import { Alert, Button, Drawer, Segmented, Space, Spin, Tag, Typography } from 'antd'
import { FileTextOutlined, ReloadOutlined } from '@ant-design/icons'
import { getRepoFileContent, getRepoFileDiff } from '../api'
import type { RepoCommitFile, RepoFileContent, RepoFileDiff } from '../types'
import { FILE_STATUS } from './RepoCommitsDrawer'
import { DRAWER_NESTED } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

interface Props {
  open: boolean
  repoId: number | null
  sha: string | null
  file: RepoCommitFile | null
  onClose: () => void
}

// ---------------- unified diff 解析 ----------------

interface DiffRow {
  type: 'meta' | 'hunk' | 'add' | 'del' | 'ctx'
  oldNo?: number
  newNo?: number
  text: string
}

/** hunk 头：@@ -oldStart[,n] +newStart[,n] @@ */
const HUNK_RE = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/

/** 解析 unified diff 为可渲染行；未知行一律按上下文行渲染不报错（CAP-113 R1）。 */
function parseUnifiedDiff(patch: string): DiffRow[] {
  const rows: DiffRow[] = []
  let oldNo = 0
  let newNo = 0
  let inHunks = false
  for (const line of patch.split('\n')) {
    if (!inHunks) {
      // 跳过 diff --git/index/---/+++ 等头，遇到首个 hunk 才进正文；
      // 二进制文件没有 hunk，提示行（Binary files ...）按 meta 展示
      const hunk = HUNK_RE.exec(line)
      if (hunk) {
        inHunks = true
        oldNo = Number(hunk[1])
        newNo = Number(hunk[2])
        rows.push({ type: 'hunk', text: line })
      } else if (line.startsWith('Binary files') || line.startsWith('GIT binary patch')) {
        rows.push({ type: 'meta', text: line })
      }
      continue
    }
    if (line.startsWith('diff --git')) {
      inHunks = false
      continue
    }
    const hunk = HUNK_RE.exec(line)
    if (hunk) {
      oldNo = Number(hunk[1])
      newNo = Number(hunk[2])
      rows.push({ type: 'hunk', text: line })
    } else if (line.startsWith('+')) {
      rows.push({ type: 'add', newNo: newNo++, text: line })
    } else if (line.startsWith('-')) {
      rows.push({ type: 'del', oldNo: oldNo++, text: line })
    } else if (line.startsWith('\\')) {
      rows.push({ type: 'meta', text: line })
    } else {
      rows.push({ type: 'ctx', oldNo: oldNo++, newNo: newNo++, text: line })
    }
  }
  return rows
}

const ROW_STYLE: Record<DiffRow['type'], CSSProperties> = {
  add: { background: '#e6ffec' },
  del: { background: '#ffebe9' },
  hunk: { background: '#f1f8ff', color: '#57606a' },
  meta: { background: '#fff8e6', color: '#8a6d1d' },
  ctx: {},
}

const GUTTER: CSSProperties = {
  width: 48,
  minWidth: 48,
  padding: '0 8px',
  textAlign: 'right',
  color: '#8c8c8c',
  userSelect: 'none',
  borderRight: '1px solid #f0f0f0',
}

/** GitHub 风格 unified diff 渲染（双列行号 + 增删行着色）。 */
function DiffView({ patch }: { patch: string }) {
  const rows = useMemo(() => parseUnifiedDiff(patch), [patch])
  if (rows.length === 0) {
    return <Alert type="info" showIcon message="该文件无文本 diff（如纯改名/权限变更）" />
  }
  return (
    <div
      style={{
        border: '1px solid #f0f0f0',
        borderRadius: 6,
        overflow: 'auto',
        fontFamily: 'monospace',
        fontSize: 12,
        lineHeight: '20px',
        maxHeight: '70vh',
      }}
    >
      {rows.map((r, i) => (
        <div key={i} style={{ display: 'flex', whiteSpace: 'pre', ...ROW_STYLE[r.type] }}>
          <span style={GUTTER}>{r.oldNo ?? ''}</span>
          <span style={GUTTER}>{r.newNo ?? ''}</span>
          <span style={{ paddingLeft: 8, paddingRight: 8, flex: 1 }}>{r.text}</span>
        </div>
      ))}
    </div>
  )
}

/** 原文视图：等宽 + 行号。 */
function ContentView({ text }: { text: string }) {
  const lines = useMemo(() => text.split('\n'), [text])
  return (
    <div
      style={{
        border: '1px solid #f0f0f0',
        borderRadius: 6,
        overflow: 'auto',
        fontFamily: 'monospace',
        fontSize: 12,
        lineHeight: '20px',
        maxHeight: '70vh',
      }}
    >
      {lines.map((line, i) => (
        <div key={i} style={{ display: 'flex', whiteSpace: 'pre' }}>
          <span style={GUTTER}>{i + 1}</span>
          <span style={{ paddingLeft: 8, paddingRight: 8, flex: 1 }}>{line}</span>
        </div>
      ))}
    </div>
  )
}

// ---------------- 抽屉 ----------------

export default function RepoFileViewDrawer({ open, repoId, sha, file, onClose }: Props) {
  const deleted = file?.status?.charAt(0) === 'D'
  const [view, setView] = useState<'diff' | 'content'>('diff')
  const [diff, setDiff] = useState<RepoFileDiff | null>(null)
  const [content, setContent] = useState<RepoFileContent | null>(null)
  const [diffErr, setDiffErr] = useState(false)
  const [contentErr, setContentErr] = useState(false)

  const loadDiff = () => {
    if (!repoId || !sha || !file) return
    setDiffErr(false)
    getRepoFileDiff(repoId, sha, file.path, file.oldPath)
      .then(setDiff)
      .catch((e) => {
        setDiffErr(true)
        showError(e, '加载文件变更失败')
      })
  }

  // 删除文件取父提交版本（path 用 oldPath ?? path）
  const loadContent = () => {
    if (!repoId || !sha || !file) return
    setContentErr(false)
    getRepoFileContent(repoId, sha, file.oldPath ?? file.path, deleted)
      .then(setContent)
      .catch((e) => {
        setContentErr(true)
        showError(e, '加载文件原文失败')
      })
  }

  // 打开（或换文件）时重置并默认加载 diff
  useEffect(() => {
    if (!open) return
    setView('diff')
    setDiff(null)
    setContent(null)
    setDiffErr(false)
    setContentErr(false)
    loadDiff()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, repoId, sha, file?.path, file?.oldPath, file?.status])

  // 切到原文 tab 才懒加载（当次抽屉实例内缓存，重复切回不重复请求）
  useEffect(() => {
    if (open && view === 'content' && !content && !contentErr) loadContent()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, open, content, contentErr])

  const meta = FILE_STATUS[file?.status?.charAt(0) ?? '']
  const titlePath = file ? (file.oldPath ? `${file.oldPath} → ${file.path}` : file.path) : ''
  const err = view === 'diff' ? diffErr : contentErr
  const ready = view === 'diff' ? diff != null : content != null

  return (
    <Drawer
      title={
        <Space size={8}>
          <FileTextOutlined />
          <Typography.Text style={{ fontFamily: 'monospace' }} copyable={!!file}>
            {titlePath}
          </Typography.Text>
          {meta && <Tag color={meta.color}>{meta.label}</Tag>}
        </Space>
      }
      open={open}
      onClose={onClose}
      width={DRAWER_NESTED}
      destroyOnHidden
    >
      <Space style={{ marginBottom: 12 }} size={12}>
        <Segmented
          value={view}
          onChange={(v) => setView(v as 'diff' | 'content')}
          options={[
            { value: 'diff', label: '变更对比' },
            { value: 'content', label: '查看原文' },
          ]}
        />
        {view === 'diff' && diff && !diff.binary && diff.additions != null && (
          <Typography.Text style={{ fontFamily: 'monospace' }}>
            <span style={{ color: '#1a7f37' }}>+{diff.additions}</span>{' '}
            <span style={{ color: '#cf1322' }}>-{diff.deletions}</span>
          </Typography.Text>
        )}
      </Space>

      {err ? (
        <Alert
          type="error"
          showIcon
          message="加载失败"
          action={
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={view === 'diff' ? loadDiff : loadContent}
            >
              重试
            </Button>
          }
        />
      ) : !ready ? (
        <Spin style={{ display: 'block', margin: '24px auto' }} />
      ) : view === 'diff' && diff ? (
        <>
          {diff.binary && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 8 }}
              message="二进制文件，仅显示 git 提示信息"
            />
          )}
          {diff.truncated && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 8 }}
              message="diff 过大，仅显示前 2000 行"
            />
          )}
          <DiffView patch={diff.patch} />
        </>
      ) : content ? (
        <>
          {deleted && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 8 }}
              message="该文件在此次提交中被删除，以下为删除前（父提交）内容"
            />
          )}
          {content.binary ? (
            <Alert
              type="info"
              showIcon
              message={`二进制文件不支持预览（${(content.size / 1024).toFixed(1)} KB）`}
            />
          ) : content.truncated ? (
            <Alert
              type="info"
              showIcon
              message={`文件超过 512KB（${(content.size / 1024).toFixed(0)} KB），不支持全文预览，请查看变更对比`}
            />
          ) : (
            <ContentView text={content.content} />
          )}
        </>
      ) : null}
    </Drawer>
  )
}
