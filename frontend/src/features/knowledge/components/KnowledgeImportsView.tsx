// CAP-86 上传批次表：全局视图（可按库过滤）与库内「手动上传」页签（固定库）共用。
import { useCallback, useEffect, useState } from 'react'
import { Button, Modal, Select, Space, Table, Tag, Tooltip, Typography } from 'antd'
import { ReloadOutlined, SyncOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listImports, retryImport, retryImportFile } from '../api'
import type { ImportBatch, ImportBatchFile, KnowledgeBase } from '../types'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const IMPORT_STATUS: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  DONE: { color: 'success', text: '完成' },
  PARTIAL: { color: 'warning', text: '部分失败' },
  FAILED: { color: 'error', text: '失败' },
  CANCELLED: { color: 'default', text: '已取消' },
}

const FILE_STATUS: Record<string, { color: string; text: string }> = {
  pending: { color: 'default', text: '待处理' },
  parsing: { color: 'processing', text: '解析中' },
  indexed: { color: 'success', text: '已入库' },
  skipped: { color: 'default', text: '未变更' },
  failed: { color: 'error', text: '失败' },
}

export default function KnowledgeImportsView({
  bases,
  kbId,
  refreshKey = 0,
  filterKbId,
  hideToolbar = false,
  onOpenEntry,
}: {
  bases: KnowledgeBase[]
  /** 传了 = 只看本库批次（库内导入页签） */
  kbId?: number
  refreshKey?: number
  /** 受控「按库过滤」（hideToolbar 时生效：父组件把过滤/刷新收进 Card extra） */
  filterKbId?: number
  /** 隐藏内置工具行（刷新/过滤），由父页面 extra 托管 */
  hideToolbar?: boolean
  onOpenEntry: (entryId: number) => void
}) {
  const [innerFilterKbId, setInnerFilterKbId] = useState<number | undefined>(undefined)
  const [imports, setImports] = useState<ImportBatch[]>([])
  const [loading, setLoading] = useState(false)
  const [retrying, setRetrying] = useState<string | null>(null)
  const [retryingFile, setRetryingFile] = useState<string | null>(null)

  const kbName = new Map<number, string>(bases.map((b) => [b.id, b.name]))
  const effFilterKbId = hideToolbar ? filterKbId : innerFilterKbId

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setImports(await listImports(kbId ?? effFilterKbId))
    } catch (e) {
      showError(e, '加载上传批次失败')
    } finally {
      setLoading(false)
    }
  }, [kbId, effFilterKbId])

  // 有运行中的批次才轮询；否则依赖上传后的 refreshKey 与手动刷新，避免历史批次下的常驻请求
  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    if (refreshKey > 0) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey])

  useEffect(() => {
    if (!imports.some((b) => b.status === 'RUNNING')) return
    const timer = window.setInterval(load, 3000)
    return () => window.clearInterval(timer)
  }, [imports, load])

  const onRetry = (batch: ImportBatch) => {
    Modal.confirm({
      centered: true,
      title: `重试批次「${batch.name}」的失败文件？`,
      content: `将重新解析并入库 ${batch.failedCount} 个失败文件；已成功的文件按内容哈希跳过，不会重复入库。`,
      okText: '重试',
      cancelText: '取消',
      onOk: async () => {
        setRetrying(batch.id)
        try {
          await retryImport(batch.id)
          await load()
        } catch (e) {
          showError(e, '重试失败')
        } finally {
          setRetrying(null)
        }
      },
    })
  }

  const onRetryFile = (batch: ImportBatch, file: ImportBatchFile) => {
    Modal.confirm({
      centered: true,
      title: `重试文件「${file.relativePath}」？`,
      content: '将重新解析并入库该文件；同路径内容未变会跳过，有变更则升版本并重建索引。',
      okText: '重试',
      cancelText: '取消',
      onOk: async () => {
        const key = `${batch.id}:${file.id}`
        setRetryingFile(key)
        try {
          await retryImportFile(batch.id, file.id)
          await load()
        } catch (e) {
          showError(e, '单文件重试失败')
        } finally {
          setRetryingFile(null)
        }
      },
    })
  }

  const columns: ColumnsType<ImportBatch> = [
    {
      title: '批次',
      dataIndex: 'name',
      ellipsis: true,
      render: (n: string, r) => (
        <Space direction="vertical" size={2}>
          <span>{n}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId) ?? `#${r.kbId}`} · {r.id.slice(0, 12)}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const m = IMPORT_STATUS[s] ?? { color: 'default', text: s }
        return <Tag color={m.color}>{m.text}</Tag>
      },
    },
    {
      title: '结果',
      key: 'counts',
      width: 190,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color="green">成功 {r.successCount}</Tag>
          {r.skippedCount > 0 && <Tag>跳过 {r.skippedCount}</Tag>}
          {r.failedCount > 0 && <Tag color="red">失败 {r.failedCount}</Tag>}
        </Space>
      ),
    },
    { title: '文件数', dataIndex: 'totalFiles', width: 80 },
    { title: '提交人', dataIndex: 'createdBy', width: 110, ellipsis: true },
    { title: '时间', dataIndex: 'createdAt', width: 165, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      width: 100,
      render: (_, r) => (
        <Tooltip title={r.status === 'RUNNING' ? '批次运行中，结束后可重试失败项' : '失败/跳过的文件重新解析'}>
          <Button
            size="small"
            icon={<SyncOutlined />}
            loading={retrying === r.id}
            disabled={r.status === 'RUNNING' || r.failedCount === 0}
            onClick={() => onRetry(r)}
          >
            重试
          </Button>
        </Tooltip>
      ),
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      {!hideToolbar && (
        <Space size={8} wrap style={{ marginBottom: 10, flexShrink: 0 }}>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          {!kbId && (
            <Select
              allowClear
              placeholder="按知识库过滤"
              style={{ minWidth: 220 }}
              value={innerFilterKbId}
              onChange={setInnerFilterKbId}
              options={bases.map((b) => ({ value: b.id, label: b.name }))}
              showSearch
              optionFilterProp="label"
            />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            每次上传生成一个批次，逐文件展示解析/入库结果；运行中会自动轮询。失败文件可单条或整批重试（按内容哈希幂等，不会重复入库）。
          </Typography.Text>
        </Space>
      )}
      <FitTable<ImportBatch>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={imports}
        pagination={LIST_PAGINATION}
        expandable={{
          expandedRowRender: (batch) => (
            <Table<ImportBatchFile>
              rowKey="id"
              size="small"
              pagination={false}
              dataSource={batch.files}
              scroll={{ y: 240 }}
              columns={[
                {
                  title: '文件',
                  dataIndex: 'relativePath',
                  ellipsis: true,
                  render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 100,
                  render: (s: string) => {
                    const m = FILE_STATUS[s] ?? { color: 'default', text: s }
                    return <Tag color={m.color}>{m.text}</Tag>
                  },
                },
                {
                  title: '说明',
                  dataIndex: 'error',
                  render: (err: string | null, r) =>
                    err ? (
                      <Typography.Text type="danger" style={{ fontSize: 12 }}>
                        {err}
                      </Typography.Text>
                    ) : r.entryId ? (
                      <Typography.Link style={{ fontSize: 12 }} onClick={() => onOpenEntry(r.entryId as number)}>
                        条目 #{r.entryId}
                      </Typography.Link>
                    ) : (
                      '-'
                    ),
                },
                {
                  title: '操作',
                  width: 86,
                  render: (_, r) =>
                    r.status === 'failed' ? (
                      <Button
                        size="small"
                        loading={retryingFile === `${batch.id}:${r.id}`}
                        onClick={() => onRetryFile(batch, r)}
                      >
                        重试
                      </Button>
                    ) : null,
                },
              ]}
            />
          ),
          rowExpandable: (b) => b.files.length > 0,
        }}
        locale={{ emptyText: '暂无上传批次。点右上角「上传文档」导入文件/文件夹/ZIP。' }}
      />
    </div>
  )
}
