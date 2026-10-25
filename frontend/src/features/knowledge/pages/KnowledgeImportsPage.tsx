// CAP-86 FR-03 文档导入：统一上传入口（文件/文件夹/ZIP）+ 全部批次列表（按库过滤、失败重试）。
import { useEffect, useState } from 'react'
import { Button, Card, Select, Space, Typography } from 'antd'
import { ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { listBases } from '../api'
import type { KnowledgeBase } from '../types'
import KnowledgeImportsView from '../components/KnowledgeImportsView'
import KnowledgeUploadDrawer from '../components/KnowledgeUploadDrawer'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

export default function KnowledgeImportsPage() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [uploadOpen, setUploadOpen] = useState(false)
  const [importsRefresh, setImportsRefresh] = useState(0)
  const [filterKbId, setFilterKbId] = useState<number | undefined>(undefined)
  const [entryDetail, setEntryDetail] = useState<{ entryId: number } | null>(null)

  const loadBases = () => {
    listBases()
      .then(setBases)
      .catch((e) => showError(e, '加载知识库失败'))
  }

  useEffect(loadBases, [])

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="文档导入"
      extra={
        <Space wrap>
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="按知识库过滤"
            style={{ minWidth: 220 }}
            value={filterKbId}
            onChange={setFilterKbId}
            options={bases.map((b) => ({ value: b.id, label: b.name }))}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              setImportsRefresh((n) => n + 1)
              loadBases()
            }}
          >
            刷新
          </Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
            上传文档
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        每次上传生成一个批次，逐文件查看解析/入库结果；运行中自动轮询，失败可单条或整批重试。
      </Typography.Paragraph>
      <KnowledgeImportsView
        bases={bases}
        refreshKey={importsRefresh}
        filterKbId={filterKbId}
        hideToolbar
        onOpenEntry={(entryId) => setEntryDetail({ entryId })}
      />

      <KnowledgeUploadDrawer
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onSubmitted={() => {
          setUploadOpen(false)
          setImportsRefresh((n) => n + 1)
          loadBases()
        }}
      />
      <KnowledgeEntryDrawer entryId={entryDetail?.entryId} onClose={() => setEntryDetail(null)} />
    </Card>
  )
}
