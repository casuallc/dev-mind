// 项目「上下文」页（CAP-33 FR-06）：当前项目实际会注入/可用的上下文资产只读清单
// （知识条目 / Skills / 文档三组，GET /api/projects/{id}/context-assets 聚合）。
// 管理入口在各组右上角，跳 /admin 对应管理页。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Space, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listContextAssets } from '../api'
import type { AssetGroup, ProjectAssetItem } from '../types'
import { useCurrentProject } from '../../../app/useCurrentProject'
import { pageRootScrollStyle } from '../../../shared/utils/pageLayout'

/** kind → 展示名 + 管理页跳转；未知 kind 用兜底配置 */
const GROUP_META: Record<string, { title: string; adminPath: string; hint: string }> = {
  knowledge: {
    title: '知识条目',
    adminPath: '/admin/knowledge',
    hint: '创建会话时按项目标签自动命中（global 无标签条目恒命中）',
  },
  skill: {
    title: 'Skills',
    adminPath: '/admin/skills',
    hint: '项目私有 ACTIVE 全量注入；全局 skill 需在场景里显式绑定',
  },
  doc: {
    title: '文档',
    adminPath: '/admin/docs',
    hint: '文档不自动注入，需在场景里显式绑定（摘要进 CLAUDE.md + 全文物化）',
  },
}
const FALLBACK_META = { title: '资产', adminPath: '/admin', hint: '' }

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
  const navigate = useNavigate()
  const { projectId, project } = useCurrentProject()
  const [groups, setGroups] = useState<AssetGroup[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      setGroups(await listContextAssets(projectId))
    } catch (e) {
      message.error(`加载上下文资产失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    load()
  }, [load])

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
    <Space direction="vertical" size={12} style={{ width: '100%', ...pageRootScrollStyle }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        当前项目（{project?.name ?? projectId}）的会话上下文资产：创建会话/场景问答时经装配管线注入沙箱
        （CLAUDE.md + .claude/skills/ + .devmind/docs/）。此页只读，维护请去对应管理页。
      </Typography.Paragraph>
      {groups.map((g) => {
        const meta = GROUP_META[g.kind] ?? { ...FALLBACK_META, title: g.kind }
        return (
          <Card
            key={g.kind}
            title={meta.title}
            extra={
              <Space>
                <Button icon={<ReloadOutlined />} onClick={load}>
                  刷新
                </Button>
                <Button icon={<SettingOutlined />} onClick={() => navigate(meta.adminPath)}>
                  管理
                </Button>
              </Space>
            }
          >
            {meta.hint && (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
                {meta.hint}
              </Typography.Paragraph>
            )}
            <Table
              rowKey="ref"
              loading={loading}
              columns={columns}
              dataSource={g.items}
              pagination={false}
              locale={{ emptyText: `暂无${meta.title}。点右上角「管理」去维护。` }}
            />
          </Card>
        )
      })}
    </Space>
  )
}
