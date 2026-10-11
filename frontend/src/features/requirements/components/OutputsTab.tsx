// 需求详情页「开发产出」Tab：会话收尾产出（SUMMARY 产物，如 dev-summary.md）的评审视图。
// 分组规则：产物带 workItemId 归到对应工作单元小节，否则进「需求级」小节（CAP-52 需求级开发会话）；
// 产物正文不复制——path 存 sessionId#fileName，点开预览时回读 session_outputs。
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Button, Drawer, Empty, Space, Spin, Tag, Typography } from 'antd'
import { FileTextOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import type { RequirementOverview } from '../types'
import { getSessionOutput } from '../../sessions/api'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_WIDE } from '../../../shared/utils/overlay'

type SummaryItem = RequirementOverview['artifacts'][number]

/** path = "sessionId#fileName"（登记端约定的引用格式）；老数据/异常格式返回 null 跳过 */
function parseRef(path?: string): { sessionId: string; fileName: string } | null {
  if (!path) return null
  const idx = path.indexOf('#')
  if (idx <= 0 || idx === path.length - 1) return null
  return { sessionId: path.slice(0, idx), fileName: path.slice(idx + 1) }
}

export default function OutputsTab({ overview }: { overview: RequirementOverview }) {
  const navigate = useNavigate()
  const [preview, setPreview] = useState<{ fileName: string; sessionId: string } | null>(null)
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(false)

  // SUMMARY 产物按工作单元分组：WI 小节顺序跟随工作单元 Tab，需求级（无 workItemId）垫底
  const groups = useMemo(() => {
    const summaries = overview.artifacts.filter(
      (a) => a.type === 'SUMMARY' && a.producerType === 'SESSION' && parseRef(a.path),
    )
    const byWi = new Map<string, SummaryItem[]>()
    const requirementLevel: SummaryItem[] = []
    for (const a of summaries) {
      if (a.workItemId) {
        const list = byWi.get(a.workItemId) ?? []
        list.push(a)
        byWi.set(a.workItemId, list)
      } else {
        requirementLevel.push(a)
      }
    }
    const wiOrder = overview.workItems.map((w) => w.id)
    const orderedWiIds = [
      ...wiOrder.filter((id) => byWi.has(id)),
      // 兜底：产物指向已删除/不在清单的 WI 也能看到
      ...[...byWi.keys()].filter((id) => !wiOrder.includes(id)),
    ]
    return { byWi, orderedWiIds, requirementLevel, total: summaries.length }
  }, [overview.artifacts, overview.workItems])

  useEffect(() => {
    if (!preview) {
      setContent('')
      return
    }
    setLoading(true)
    getSessionOutput(preview.sessionId, preview.fileName)
      .then((r) => setContent(r.content))
      .catch((e) => showError(e, '读取产出失败'))
      .finally(() => setLoading(false))
  }, [preview])

  if (groups.total === 0) {
    return (
      <Empty
        style={{ margin: '40px 0' }}
        description="暂无开发产出（开发会话写 .devmind/output/ 并在结束后自动登记到这里）"
      />
    )
  }

  const wiTitle = (id: string) => overview.workItems.find((w) => w.id === id)

  const renderItem = (a: SummaryItem) => {
    const ref = parseRef(a.path)!
    return (
      <div
        key={a.id}
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '6px 8px',
          borderRadius: 6,
          border: '1px solid rgba(128,128,128,0.2)',
          marginBottom: 6,
        }}
      >
        <Space size={8}>
          <FileTextOutlined />
          <Typography.Text code style={{ fontSize: 12 }}>
            {ref.fileName}
          </Typography.Text>
          <Typography.Link
            style={{ fontSize: 12 }}
            onClick={() => navigate(`/sessions?sid=${ref.sessionId}`)}
          >
            会话 {ref.sessionId}
          </Typography.Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {fmtTime(a.createdAt)}
          </Typography.Text>
        </Space>
        <Button size="small" type="link" onClick={() => setPreview(ref)}>
          查看
        </Button>
      </div>
    )
  }

  const renderGroup = (title: ReactNode, items: SummaryItem[]) => (
    <div key={String(title)} style={{ marginBottom: 16 }}>
      <div style={{ marginBottom: 8 }}>{title}</div>
      {items.map(renderItem)}
    </div>
  )

  return (
    <div>
      {groups.orderedWiIds.map((wiId) => {
        const wi = wiTitle(wiId)
        return renderGroup(
          <Space size={8}>
            <Typography.Text strong>{wi ? wi.title : wiId}</Typography.Text>
            {wi && <Tag>{wi.type}</Tag>}
            {wi && <Tag color={wi.status === 'DONE' ? 'green' : 'blue'}>{wi.status}</Tag>}
          </Space>,
          groups.byWi.get(wiId)!,
        )
      })}
      {groups.requirementLevel.length > 0 &&
        renderGroup(<Typography.Text strong>需求级</Typography.Text>, groups.requirementLevel)}

      <Drawer
        title={preview?.fileName ?? ''}
        open={!!preview}
        onClose={() => setPreview(null)}
        width={DRAWER_WIDE}
        destroyOnHidden
      >
        {loading ? (
          <Spin style={{ display: 'block', margin: '60px auto' }} />
        ) : (
          <Markdown content={content} />
        )}
      </Drawer>
    </div>
  )
}
