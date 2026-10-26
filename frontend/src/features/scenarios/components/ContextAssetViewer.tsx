// 项目「上下文」页资产只读查看抽屉：文档点名称看全文（列表只出摘要）。
// 数据走公开的 GET /documents/{id}——项目上下文页所有登录用户可见，
// 不能直接跳 /admin 管理页（仅 ADMIN）。
import { useEffect, useState } from 'react'
import { Alert, Drawer, Space, Spin, Tag, Typography } from 'antd'
import { getDoc } from '../../docs/api'
import { KIND_LABEL, STATUS_LABEL } from '../../docs/types'
import type { DocDetail, DocKind } from '../../docs/types'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'

export interface ContextAssetRef {
  kind: string
  ref: string
  name: string
}

const docKindColor = (k: DocKind) =>
  k === 'requirement' ? 'blue' : k === 'design' ? 'geekblue' : k === 'analysis' ? 'gold' : k === 'api-suite' ? 'purple' : 'cyan'

const docStatusColor = (s: string) => (s === 'draft' ? 'default' : s === 'pending_confirm' ? 'gold' : 'green')

export default function ContextAssetViewer({
  asset,
  onClose,
}: {
  asset: ContextAssetRef | null
  onClose: () => void
}) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [doc, setDoc] = useState<DocDetail | null>(null)

  useEffect(() => {
    if (!asset) return
    setLoading(true)
    setError(null)
    setDoc(null)
    let alive = true
    const task =
      asset.kind === 'doc'
        ? getDoc(Number(asset.ref)).then((d) => { if (alive) setDoc(d) })
        : Promise.resolve()
    task
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [asset])

  return (
    <Drawer
      title={asset?.name ?? '查看内容'}
      open={!!asset}
      onClose={onClose}
      width={DRAWER_SCREEN}
      destroyOnHidden
    >
      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}>
          <Spin />
        </div>
      ) : error ? (
        <Alert type="error" showIcon message="加载内容失败" description={error} />
      ) : doc ? (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space size={6} wrap>
            <Tag color={docKindColor(doc.kind)}>{KIND_LABEL[doc.kind] ?? doc.kind}</Tag>
            <Tag color={docStatusColor(doc.status)}>{STATUS_LABEL[doc.status] ?? doc.status}</Tag>
            <Tag>v{doc.currentVersion}</Tag>
            {doc.tags.map((t) => <Tag key={t}>{t}</Tag>)}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              更新于 {fmtTime(doc.updatedAt)}
            </Typography.Text>
          </Space>
          <Markdown content={doc.contentMd} />
        </Space>
      ) : null}
    </Drawer>
  )
}
