// CAP-03 文档详情页：单卡片布局（对齐 Skill 详情），顶部标题栏集中所有操作；
// 只读渲染正文 / 编辑抽屉（DocEditDrawer）/ 版本历史抽屉 / 左右对照 diff / 回退 / 状态机。
import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Drawer,
  Empty,
  message,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  HistoryOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { canWrite } from '../../auth/authStore'
import { deleteDoc, docDiff, getDoc, listDocVersions, revertDoc, transitionDoc } from '../api'
import { KIND_LABEL, STATUS_LABEL } from '../types'
import type { DiffResult, DocDetail, DocVersion } from '../types'
import DocEditDrawer from '../components/DocEditDrawer'
import { getProject } from '../../projects/api'
import { getRequirement } from '../../requirements/api'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

const statusTag = (s: string) => (
  <Tag color={s === 'draft' ? 'default' : s === 'pending_confirm' ? 'gold' : 'green'}>{STATUS_LABEL[s as keyof typeof STATUS_LABEL] ?? s}</Tag>
)

// ---------- 左右对照 diff ----------
// 后端给的是简化 unified 行（首字符 +/-/空格），前端解析成双栏行：
// 连续的 - 块紧跟 + 块视为「修改」逐行配对，落单的行另一侧留空。

type SideCell = { text: string; kind: 'ctx' | 'add' | 'del' | 'empty' }
interface DiffRow {
  key: number
  left: SideCell
  right: SideCell
}

function toSideBySide(lines: string[]): DiffRow[] {
  const rows: DiffRow[] = []
  let i = 0
  while (i < lines.length) {
    const c = lines[i].charAt(0)
    if (c === '-') {
      const dels: string[] = []
      while (i < lines.length && lines[i].startsWith('-')) dels.push(lines[i++].slice(1))
      const adds: string[] = []
      while (i < lines.length && lines[i].startsWith('+')) adds.push(lines[i++].slice(1))
      for (let k = 0; k < Math.max(dels.length, adds.length); k++) {
        rows.push({
          key: rows.length,
          left: k < dels.length ? { text: dels[k], kind: 'del' } : { text: '', kind: 'empty' },
          right: k < adds.length ? { text: adds[k], kind: 'add' } : { text: '', kind: 'empty' },
        })
      }
    } else if (c === '+') {
      rows.push({ key: rows.length, left: { text: '', kind: 'empty' }, right: { text: lines[i++].slice(1), kind: 'add' } })
    } else {
      const text = c === ' ' ? lines[i].slice(1) : lines[i]
      i++
      rows.push({ key: rows.length, left: { text, kind: 'ctx' }, right: { text, kind: 'ctx' } })
    }
  }
  return rows
}

// 行内差异标记：配对的删/增两行取公共前后缀，仅高亮中间变化段
function splitChange(a: string, b: string): { prefix: string; aMid: string; bMid: string; suffix: string } {
  let p = 0
  while (p < a.length && p < b.length && a[p] === b[p]) p++
  let s = 0
  while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] === b[b.length - 1 - s]) s++
  return {
    prefix: a.slice(0, p),
    aMid: a.slice(p, a.length - s),
    bMid: b.slice(p, b.length - s),
    suffix: a.slice(a.length - s),
  }
}

const CELL_BG: Record<SideCell['kind'], string> = {
  ctx: 'transparent',
  add: '#f6ffed',
  del: '#fff1f0',
  empty: '#fafafa',
}

function DiffCell({ cell, other }: { cell: SideCell; other: SideCell }) {
  // 删/增配对时做行内高亮；整行新增/删除无需对比
  const paired = (cell.kind === 'del' && other.kind === 'add') || (cell.kind === 'add' && other.kind === 'del')
  let content: React.ReactNode = cell.text || ' '
  if (paired) {
    const del = cell.kind === 'del' ? cell : other
    const add = cell.kind === 'add' ? cell : other
    const { prefix, aMid, bMid, suffix } = splitChange(del.text, add.text)
    const mid = cell.kind === 'del' ? aMid : bMid
    content = (
      <>
        {prefix}
        <span style={{ background: cell.kind === 'del' ? '#ffa39e' : '#95de64', borderRadius: 2 }}>{mid || ' '}</span>
        {suffix}
      </>
    )
  }
  return (
    <div
      style={{
        flex: 1,
        minWidth: 0,
        padding: '0 8px',
        background: CELL_BG[cell.kind],
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
      }}
    >
      {content}
    </div>
  )
}

function SideBySideDiff({ diff }: { diff: DiffResult }) {
  const rows = toSideBySide(diff.lines)
  return (
    <div style={{ fontFamily: 'monospace', fontSize: 12, lineHeight: '22px', border: '1px solid #f0f0f0', borderRadius: 4 }}>
      {rows.map((r) => (
        <div key={r.key} style={{ display: 'flex', borderBottom: '1px solid #f5f5f5' }}>
          <DiffCell cell={r.left} other={r.right} />
          <div style={{ width: 1, background: '#f0f0f0', flex: 'none' }} />
          <DiffCell cell={r.right} other={r.left} />
        </div>
      ))}
    </div>
  )
}

export default function DocEditorPage() {
  const { id } = useParams<{ id: string }>()
  const docId = Number(id)
  const navigate = useNavigate()

  const [doc, setDoc] = useState<DocDetail | null>(null)
  const [versions, setVersions] = useState<DocVersion[]>([])
  const [loading, setLoading] = useState(true)
  // 归属名称解析：详情只带 projectId/requirementId，另查名称便于阅读关联（查不到回退显示 id）
  const [projectName, setProjectName] = useState<string | null>(null)
  const [requirementTitle, setRequirementTitle] = useState<string | null>(null)

  const [editOpen, setEditOpen] = useState(false)
  const [versionOpen, setVersionOpen] = useState(false)

  const [viewing, setViewing] = useState<DocDetail | null>(null) // 历史版本只读视图
  const [diff, setDiff] = useState<DiffResult | null>(null)
  const [diffFor, setDiffFor] = useState<number | null>(null)

  const load = useCallback(async (version?: number) => {
    if (!docId) return
    try {
      const [d, vs] = await Promise.all([getDoc(docId, version), listDocVersions(docId)])
      if (version) {
        setViewing(d)
      } else {
        setDoc(d)
        setViewing(null)
        setProjectName(null)
        setRequirementTitle(null)
        if (d.projectId) getProject(d.projectId).then((p) => setProjectName(p.name)).catch(() => {})
        if (d.projectId && d.requirementId) {
          getRequirement(d.projectId, d.requirementId).then((r) => setRequirementTitle(r.title)).catch(() => {})
        }
      }
      setVersions(vs)
    } catch (e) {
      showError(e, '加载文档失败')
    } finally {
      setLoading(false)
    }
  }, [docId])

  useEffect(() => {
    setLoading(true)
    load()
  }, [load])

  const onTransition = async (action: string, okText: string, tip: string) => {
    Modal.confirm({
      centered: true,
      title: okText + '？',
      content: tip,
      okText,
      cancelText: '取消',
      onOk: async () => {
        try {
          await transitionDoc(docId, action)
          message.success(`已${okText}`)
          await load()
        } catch (e) {
          showError(e, '操作失败')
        }
      },
    })
  }

  const onDelete = () => {
    Modal.confirm({
      centered: true,
      title: '删除该文档？',
      content: '会删除文档与全部历史版本记录，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteDoc(docId)
          message.success('已删除')
          navigate('/admin/docs')
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }

  const onDiff = async (v: number) => {
    try {
      setDiff(await docDiff(docId, v))
      setDiffFor(v)
    } catch (e) {
      showError(e, '获取 diff 失败')
    }
  }

  const onRevert = (v: number) => {
    Modal.confirm({
      centered: true,
      title: `回退到 v${v}？`,
      content: '将 v' + v + ' 的内容保存为新版本（当前内容进入历史，不丢失）。',
      okText: '回退',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await revertDoc(docId, v)
          message.success(`已回退并生成 v${r.versionNo}`)
          await load()
        } catch (e) {
          showError(e, '回退失败')
        }
      },
    })
  }

  const versionColumns: ColumnsType<DocVersion> = [
    {
      title: '版本',
      dataIndex: 'versionNo',
      width: 70,
      render: (v: number, r) =>
        v === doc?.currentVersion ? (
          <Typography.Text strong>v{v}</Typography.Text>
        ) : (
          // 点版本号直接查看该历史版本（关闭抽屉、正文切换）
          <a onClick={() => { setVersionOpen(false); load(r.versionNo) }}>v{v}</a>
        ),
    },
    { title: '变更说明', dataIndex: 'changeNote', ellipsis: true, render: (n) => n || <Typography.Text type="secondary">-</Typography.Text> },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t) => fmtTime(t) },
    {
      title: '操作',
      width: 200,
      render: (_, r) => (
        <Space size={4}>
          <Button onClick={() => onDiff(r.versionNo)}>
            与当前 diff
          </Button>
          <Button
            disabled={r.versionNo === doc?.currentVersion}
            onClick={() => onRevert(r.versionNo)}
          >
            回退
          </Button>
        </Space>
      ),
    },
  ]

  const currentContent = viewing ? viewing.contentMd : doc?.contentMd ?? ''
  const displayTitle = viewing ? `${doc?.title ?? '文档'}（历史 v${viewing.versionNo}）` : doc?.title

  // 所有操作集中在顶部标题栏；查看历史版本时只留「回到当前版本」
  const actions = viewing ? (
    <Button type="primary" onClick={() => load()}>
      回到当前版本
    </Button>
  ) : (
    <>
      {canWrite() && (
        <Button type="primary" icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
          编辑
        </Button>
      )}
      {doc?.status === 'draft' && (
        <Button onClick={() => onTransition('submit', '提交确认', '进入待确认状态，可进一步冻结为基线。')}>
          提交确认
        </Button>
      )}
      {doc?.status === 'pending_confirm' && (
        <Button onClick={() => onTransition('freeze', '冻结', '冻结后为基线，后续变更必须生成新版本并填写变更说明。')}>
          冻结
        </Button>
      )}
      {doc?.status === 'frozen' && (
        <Button onClick={() => onTransition('unfreeze', '解除冻结', '回到草稿态，可随意编辑。')}>
          解除冻结
        </Button>
      )}
      {canWrite() && (
        <Button danger icon={<DeleteOutlined />} onClick={onDelete}>
          删除
        </Button>
      )}
    </>
  )

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
      {loading ? (
        <Card>
          <Spin />
        </Card>
      ) : !doc ? (
        <Card>
          <Empty description="文档不存在" />
        </Card>
      ) : (
        <Card
          style={pageCardStyle}
          styles={{ body: pageCardBodyFlexStyle }}
          title={
            <Space wrap size={8}>
              <Typography.Text strong>{displayTitle}</Typography.Text>
              <Tag color={doc.kind === 'requirement' ? 'blue' : doc.kind === 'design' ? 'geekblue' : doc.kind === 'analysis' ? 'gold' : doc.kind === 'api-suite' ? 'purple' : 'cyan'}>
                {KIND_LABEL[doc.kind]}
              </Tag>
              {statusTag(doc.status)}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                v{doc.currentVersion}
                {viewing && <Badge status="processing" text={`查看历史 v${viewing.versionNo}`} />}
              </Typography.Text>
              {doc.projectId && (
                <Link to={`/projects/${doc.projectId}`}>
                  <Tag style={{ cursor: 'pointer' }}>项目: {projectName ?? doc.projectId}</Tag>
                </Link>
              )}
              {doc.requirementId && (
                <Link to={doc.projectId ? `/projects/${doc.projectId}/requirements/${doc.requirementId}` : '#'}>
                  <Tag style={{ cursor: doc.projectId ? 'pointer' : undefined }}>需求: {requirementTitle ?? doc.requirementId}</Tag>
                </Link>
              )}
              {doc.tags.map((t) => <Tag key={t}>{t}</Tag>)}
            </Space>
          }
          extra={
            <Space wrap>
              {actions}
              <Button icon={<HistoryOutlined />} onClick={() => setVersionOpen(true)}>
                历史版本 ({versions.length})
              </Button>
              <Button onClick={() => navigate('/admin/docs')}>返回列表</Button>
            </Space>
          }
        >
          {/* 正文：只读预览，滚动只发生在卡片内部；编辑走抽屉 */}
          <div style={pagePaneScrollStyle}>
            <Markdown content={currentContent} />
          </div>
        </Card>
      )}

      {doc && (
        <DocEditDrawer
          open={editOpen}
          doc={doc}
          onClose={() => setEditOpen(false)}
          onSaved={() => load()}
        />
      )}

      {/* 版本历史抽屉（点版本号查看历史 / diff / 回退） */}
      <Drawer
        title="版本历史"
        open={versionOpen}
        onClose={() => setVersionOpen(false)}
        width="70%"
      >
        <Table
          size="small"
          rowKey="versionNo"
          columns={versionColumns}
          dataSource={versions}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 个版本` }}
        />
      </Drawer>

      {/* diff 抽屉：左右对照 */}
      <Drawer
        title={diffFor ? `v${diffFor} → 当前版本的差异` : '版本差异'}
        open={!!diff}
        onClose={() => setDiff(null)}
        width="70%"
      >
        {diff && (
          <>
            {!diff.hasChanges ? (
              <Empty description="两版本内容一致" />
            ) : (
              <>
                <Typography.Paragraph type="secondary">
                  左：v{diffFor}（红=删除）· 右：当前版本（绿=新增）· 新增 {diff.additions} 行 / 删除 {diff.deletions} 行
                </Typography.Paragraph>
                <SideBySideDiff diff={diff} />
              </>
            )}
          </>
        )}
      </Drawer>
    </div>
  )
}
