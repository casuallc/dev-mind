// 项目「知识」页（CAP-33 FR-06）：当前项目实际会注入/可用的上下文资产只读清单
// （知识条目 / 文档 / Skills 三视图，GET /api/projects/{id}/context-assets 聚合）。
// 多视图切换走 Card title 里的 Segmented（布局约定：禁 Card 内套 Tabs）；维护请去 /admin 对应管理页。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Segmented, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { listContextAssets } from '../api'
import type { AssetGroup, ProjectAssetItem } from '../types'
import { useCurrentProject } from '../../../app/useCurrentProject'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

/** kind → 展示名 + 说明；数组顺序即 Segmented 视图顺序；未知 kind 用兜底配置排在最后 */
const GROUP_META: Array<{ kind: string; title: string; hint: string }> = [
  {
    kind: 'knowledge',
    title: '知识条目',
    hint: '创建会话时按项目标签自动命中（global 无标签条目恒命中）',
  },
  {
    kind: 'doc',
    title: '文档',
    hint: '文档不自动注入，需在场景里显式绑定（摘要进 CLAUDE.md + 全文物化）',
  },
  {
    kind: 'skill',
    title: 'Skills',
    hint: '项目私有 ACTIVE 全量注入；全局 skill 需在场景里显式绑定',
  },
]
const FALLBACK_TITLE = '资产'

/** extra 附加信息白名单渲染（scope/status/tags/hitCount/version），其余忽略 */
function ExtraTags({ extra }: { extra?: Record<string, unknown> }) {
  if (!extra) return null
  const show = (['scope', 'status', 'version', 'hitCount', 'tags'] as const)
    .filter((k) => extra[k] != null && extra[k] !== '')
    .map((k) => (
      <Tag key={k} style={{ fontSize: 11 }}>
        {k}: {Array.isArray(extra[k]) ? (extra[k] as unknown[]).join(',') : String(extra[k])}
      </Tag>
    ))
  return show.length > 0 ? <Space size={4} wrap>{show}</Space> : null
}

export default function ProjectContextPage() {
  const { projectId, project } = useCurrentProject()
  const [groups, setGroups] = useState<AssetGroup[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<string>('knowledge')

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      setGroups(await listContextAssets(projectId))
    } catch (e) {
      showError(e, '加载上下文资产失败')
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    load()
  }, [load])

  // 视图选项：按 GROUP_META 顺序排已知 kind，未知 kind 追加在最后
  const viewOptions = useMemo(() => {
    const known = GROUP_META.filter((m) => groups.some((g) => g.kind === m.kind))
    const unknown = groups
      .filter((g) => !GROUP_META.some((m) => m.kind === g.kind))
      .map((g) => ({ kind: g.kind, title: FALLBACK_TITLE, hint: '' }))
    return [...known, ...unknown]
  }, [groups])

  // 当前视图的数据与元信息；视图无效（如重载后该组消失）时回落到首个可用视图
  const activeKind = viewOptions.some((o) => o.kind === view) ? view : (viewOptions[0]?.kind ?? view)
  const activeMeta = GROUP_META.find((m) => m.kind === activeKind) ?? {
    kind: activeKind,
    title: FALLBACK_TITLE,
    hint: '',
  }
  const activeGroup = groups.find((g) => g.kind === activeKind)

  const columns = [
    { title: '名称', dataIndex: 'name', width: 260, ellipsis: true },
    {
      title: '摘要',
      dataIndex: 'summary',
      ellipsis: true,
      render: (s?: string) => <Typography.Text type="secondary">{s || '-'}</Typography.Text>,
    },
    {
      title: '属性',
      key: 'extra',
      width: 280,
      render: (_: unknown, r: ProjectAssetItem) => <ExtraTags extra={r.extra} />,
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>知识</span>
          <Segmented
            value={activeKind}
            onChange={(v) => setView(v as string)}
            options={viewOptions.map((o) => ({ label: o.title, value: o.kind }))}
          />
        </Space>
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        当前项目（{project?.name ?? projectId}）的会话上下文资产：创建会话/场景问答时经装配管线注入沙箱
        （CLAUDE.md + .claude/skills/ + .devmind/docs/）。此页只读，维护请去后台管理对应页面。
        {activeMeta.hint && (
          <>
            <br />
            {activeMeta.hint}
          </>
        )}
      </Typography.Paragraph>
      <Table
        rowKey="ref"
        loading={loading}
        columns={columns}
        dataSource={activeGroup?.items ?? []}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: `暂无${activeMeta.title}，可到后台管理维护。` }}
      />
    </Card>
  )
}
