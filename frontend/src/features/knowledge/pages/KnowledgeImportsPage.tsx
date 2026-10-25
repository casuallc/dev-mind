// CAP-86 FR-03 文档导入：统一上传入口（文件/文件夹/ZIP）+ 全部批次列表（按库过滤、失败重试）。
import { useEffect, useState } from 'react'
import { Button, Card, Space, Typography } from 'antd'
import { InboxOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
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
  const [entryDetail, setEntryDetail] = useState<{ entryId: number } | null>(null)

  const loadBases = () => {
    listBases()
      .then(setBases)
      .catch((e) => showError(e, '加载知识库失败'))
  }

  useEffect(loadBases, [])

  return (
    <Card style={pageCardStyle} styles={{ body: pageCardBodyFlexStyle }}>
      <div style={{ padding: '16px 20px', borderBottom: '1px solid #f0f0f0', flexShrink: 0 }}>
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <span style={{ fontSize: 20, color: '#1677ff', lineHeight: '30px' }}>
            <InboxOutlined />
          </span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Typography.Title level={4} style={{ margin: 0 }}>
              文档导入
            </Typography.Title>
            <Typography.Text type="secondary">
              每次上传生成一个批次，逐文件查看解析/入库结果；运行中自动轮询，失败可单条或整批重试。
            </Typography.Text>
          </div>
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={loadBases}>
              刷新库列表
            </Button>
            <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
              上传文档
            </Button>
          </Space>
        </div>
      </div>

      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '14px 20px' }}>
        <KnowledgeImportsView
          bases={bases}
          refreshKey={importsRefresh}
          onOpenEntry={(entryId) => setEntryDetail({ entryId })}
        />
      </div>

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
