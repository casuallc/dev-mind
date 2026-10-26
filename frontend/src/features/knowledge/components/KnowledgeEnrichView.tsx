// CAP-86 v3 知识运营·自动增强：增强配置、批量触发与逐条补全（从报表页拆出）。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Descriptions,
  InputNumber,
  Modal,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons'
import {
  getEnrichConfig,
  listEnrichEntries,
  runEnrich,
  runEntryEnrich,
} from '../api'
import type { DocType, EnrichConfig, EnrichEntry, KnowledgeBase } from '../types'
import { DOC_TYPE_LABEL } from '../types'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { showError } from '../../../shared/utils/showError'

/** 表格类视图：工具行固定、FitTable 撑满剩余高度 */
const sectionFlexStyle = { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' } as const

export default function KnowledgeEnrichView({
  bases,
  onOpenEntry,
}: {
  bases: KnowledgeBase[]
  onOpenEntry: (entryId: number) => void
}) {
  const [enrichConfig, setEnrichConfig] = useState<EnrichConfig | null>(null)
  const [entries, setEntries] = useState<EnrichEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [kbId, setKbId] = useState<number | undefined>(undefined)
  const [limit, setLimit] = useState(100)
  const [enrichingId, setEnrichingId] = useState<number | null>(null)
  const [batchEnriching, setBatchEnriching] = useState(false)

  const kbName = new Map<number, string>(bases.map((b) => [b.id, b.name]))

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setEntries(await listEnrichEntries(kbId, limit))
    } catch (e) {
      showError(e, '加载增强状态失败')
    } finally {
      setLoading(false)
    }
  }, [kbId, limit])

  useEffect(() => {
    getEnrichConfig()
      .then(setEnrichConfig)
      .catch((e) => showError(e, '加载增强配置失败'))
    load()
  }, [load])

  const onBatchEnrich = () => {
    const scope = kbId ? `知识库「${kbName.get(kbId) ?? kbId}」` : '全部知识库'
    Modal.confirm({
      centered: true,
      title: `立即增强${scope}（最多 ${limit} 条）？`,
      content: '增强会改写文档的分类、摘要与标签（人工锁定条目不受影响），并重建索引。',
      okText: '增强',
      cancelText: '取消',
      onOk: async () => {
        setBatchEnriching(true)
        try {
          const r = await runEnrich(kbId, limit)
          message.success(`已提交自动增强任务（最多 ${r.limit} 条），稍后刷新查看结果`)
          window.setTimeout(() => load(), 1500)
        } catch (e) {
          showError(e, '提交自动增强失败')
        } finally {
          setBatchEnriching(false)
        }
      },
    })
  }

  const onEnrichEntry = (e: EnrichEntry) => {
    Modal.confirm({
      centered: true,
      title: `补全「${e.name}」的元数据？`,
      content: e.autoEnrich
        ? '会重新生成分类、摘要与标签，并重建索引。'
        : '该条目为人工锁定；补全会恢复自动增强并覆盖现有分类、摘要与标签。',
      okText: '补全',
      cancelText: '取消',
      onOk: async () => {
        setEnrichingId(e.id)
        try {
          await runEntryEnrich(e.id)
          message.success(`已提交补全任务：${e.name}`)
          window.setTimeout(() => load(), 1500)
        } catch (err) {
          showError(err, '提交补全任务失败')
        } finally {
          setEnrichingId(null)
        }
      },
    })
  }

  const statusTag = (e: EnrichEntry) => {
    if (!e.autoEnrich) {
      return (
        <Tooltip title="已人工修正分类/摘要/标签，自动增强不再覆盖；点「补全」可恢复">
          <Tag>人工锁定</Tag>
        </Tooltip>
      )
    }
    if (e.enrichError) {
      return (
        <Tooltip title={e.enrichError}>
          <Tag color="red">失败</Tag>
        </Tooltip>
      )
    }
    const fresh = e.enrichedAt && (!e.updatedAt || new Date(e.enrichedAt).getTime() >= new Date(e.updatedAt).getTime())
    return fresh ? <Tag color="green">已增强</Tag> : <Tag color="gold">待增强</Tag>
  }

  const columns: ColumnsType<EnrichEntry> = [
    {
      title: '文档',
      key: 'name',
      ellipsis: true,
      render: (_, r) => <Typography.Link onClick={() => onOpenEntry(r.id)}>{r.name}</Typography.Link>,
    },
    {
      title: '知识库',
      key: 'kb',
      width: 150,
      ellipsis: true,
      render: (_, r) => <Tag color="purple">{kbName.get(r.kbId ?? -1) ?? `#${r.kbId}`}</Tag>,
    },
    {
      title: '类型',
      dataIndex: 'docType',
      width: 100,
      render: (t: DocType | null) => (t ? <Tag color="blue">{DOC_TYPE_LABEL[t] ?? t}</Tag> : '-'),
    },
    { title: '增强状态', key: 'status', width: 110, render: (_, r) => statusTag(r) },
    {
      title: '最近增强',
      dataIndex: 'enrichedAt',
      width: 165,
      render: (v: string | null) => (v ? fmtTime(v) : '-'),
    },
    {
      title: '失败原因',
      dataIndex: 'enrichError',
      ellipsis: true,
      render: (v: string | null) =>
        v ? (
          <Tooltip title={v}>
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              {v}
            </Typography.Text>
          </Tooltip>
        ) : (
          '-'
        ),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, r) => (
        <Button size="small" loading={enrichingId === r.id} onClick={() => onEnrichEntry(r)}>
          补全
        </Button>
      ),
    },
  ]

  return (
    <div style={sectionFlexStyle}>
      <Descriptions
        size="small"
        column={4}
        style={{ marginBottom: 12, flexShrink: 0 }}
        items={[
          {
            key: 'enabled',
            label: '自动排队',
            children: enrichConfig ? (
              enrichConfig.enabled ? (
                <Tag color="green">已开启</Tag>
              ) : (
                <Tag>已关闭（可手工触发）</Tag>
              )
            ) : (
              '-'
            ),
          },
          {
            key: 'endpoint',
            label: 'CHAT 端点',
            children: enrichConfig?.endpointId ? `#${enrichConfig.endpointId}` : '平台默认',
          },
          {
            key: 'batch',
            label: '单批上限',
            children: enrichConfig ? `${enrichConfig.batchLimit} 条` : '-',
          },
          {
            key: 'input',
            label: '正文截断',
            children: enrichConfig ? `${enrichConfig.maxInputChars} 字` : '-',
          },
        ]}
      />
      <Space style={{ marginBottom: 12, flexShrink: 0 }} wrap>
        <Select
          allowClear
          placeholder="全部知识库"
          style={{ minWidth: 200 }}
          value={kbId}
          onChange={setKbId}
          options={bases.map((b) => ({ value: b.id, label: b.name }))}
        />
        <InputNumber
          min={1}
          max={200}
          value={limit}
          onChange={(v) => setLimit(v ?? 100)}
          addonAfter="条"
          style={{ width: 130 }}
        />
        <Button type="primary" icon={<ThunderboltOutlined />} loading={batchEnriching} onClick={onBatchEnrich}>
          立即增强
        </Button>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          增强为异步任务；人工修正过的条目默认不覆盖，点「补全」可恢复自动模式。
        </Typography.Text>
      </Space>
      <FitTable<EnrichEntry>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={entries}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无条目。' }}
      />
    </div>
  )
}
