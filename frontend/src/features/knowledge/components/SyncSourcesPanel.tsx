// CAP-86 FR-05 同步源面板：从原治理控制台「同步源」页签抽出，按库下沉到库详情「飞书同步」页签。
// kbId 固定 = 只看/只建本库同步源；不传 = 全库（预留，当前仅库详情使用）。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createSyncSource,
  deleteSyncSource,
  listSyncSources,
  runSyncSource,
  updateSyncSource,
} from '../api'
import type {
  DocType,
  KnowledgeBase,
  KnowledgeSyncSource,
  KnowledgeSyncSourceInput,
  SyncSourceKind,
  SyncStats,
} from '../types'
import { DOC_TYPE_OPTIONS } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

const SYNC_STATUS: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  OK: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
}

const cfgStr = (config: Record<string, unknown>, key: string) => {
  const v = config[key]
  return v == null ? '' : String(v)
}

const cfgNum = (config: Record<string, unknown>, key: string) => {
  const n = Number(config[key])
  return Number.isFinite(n) && n > 0 ? n : undefined
}

const cfgStrArray = (config: Record<string, unknown>, key: string) => {
  const v = config[key]
  if (Array.isArray(v)) return v.map((x) => String(x))
  if (typeof v === 'string' && v.trim()) {
    return v
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
  }
  return []
}

interface SyncFormValues {
  kbId: number
  kind: SyncSourceKind
  name?: string
  enabled: boolean
  repoPath?: string
  ref?: string
  docsPath?: string
  integrationId?: number
  target?: string
  docType?: DocType
  tags?: string[]
}

export default function SyncSourcesPanel({
  bases,
  kbId,
}: {
  bases: KnowledgeBase[]
  /** 传了 = 固定本库：列表按 kbId 过滤、新建/编辑表单锁定目标库 */
  kbId?: number
}) {
  const [sources, setSources] = useState<KnowledgeSyncSource[]>([])
  const [sourcesLoading, setSourcesLoading] = useState(false)
  const [syncModalOpen, setSyncModalOpen] = useState(false)
  const [editingSync, setEditingSync] = useState<KnowledgeSyncSource | null>(null)
  const [syncSaving, setSyncSaving] = useState(false)
  const [runningSync, setRunningSync] = useState<number | null>(null)
  const [syncForm] = Form.useForm<SyncFormValues>()
  const syncKind = Form.useWatch('kind', syncForm) ?? 'GIT'

  const kbName = new Map<number, string>(bases.map((b) => [b.id, b.name]))

  const loadSync = useCallback(
    async (silent = false) => {
      if (!silent) setSourcesLoading(true)
      try {
        setSources(await listSyncSources(kbId))
      } catch (e) {
        if (!silent) showError(e, '加载同步源失败')
      } finally {
        if (!silent) setSourcesLoading(false)
      }
    },
    [kbId],
  )

  // 同步是异步任务：停留期间低频轮询，RUNNING/统计无需手动刷新
  useEffect(() => {
    loadSync()
    const timer = window.setInterval(() => loadSync(true), 5000)
    return () => window.clearInterval(timer)
  }, [loadSync])

  const openCreateSync = () => {
    setEditingSync(null)
    syncForm.resetFields()
    syncForm.setFieldsValue({ kbId, kind: 'GIT', enabled: true, ref: 'HEAD', docsPath: 'docs' })
    setSyncModalOpen(true)
  }

  const openEditSync = (s: KnowledgeSyncSource) => {
    setEditingSync(s)
    syncForm.resetFields()
    syncForm.setFieldsValue({
      kbId: s.kbId,
      kind: s.kind,
      name: s.name,
      enabled: s.enabled,
      repoPath: cfgStr(s.config, 'repoPath') || undefined,
      ref: cfgStr(s.config, 'ref') || 'HEAD',
      docsPath: cfgStr(s.config, 'docsPath') || 'docs',
      integrationId: cfgNum(s.config, 'integrationId'),
      target: cfgStr(s.config, 'target') || undefined,
      docType: (cfgStr(s.config, 'docType') || undefined) as DocType | undefined,
      tags: cfgStrArray(s.config, 'tags'),
    })
    setSyncModalOpen(true)
  }

  const commitSync = async (input: KnowledgeSyncSourceInput) => {
    setSyncSaving(true)
    try {
      if (editingSync) {
        await updateSyncSource(editingSync.id, input)
        message.success('同步源已更新')
      } else {
        await createSyncSource(input)
        message.success('同步源已创建')
      }
      setSyncModalOpen(false)
      loadSync()
    } catch (e) {
      showError(e, '保存同步源失败')
    } finally {
      setSyncSaving(false)
    }
  }

  const onSaveSync = async () => {
    const v = await syncForm.validateFields()
    const config: Record<string, unknown> = { tags: v.tags ?? [] }
    if (v.docType) config.docType = v.docType
    if (v.kind === 'GIT') {
      config.repoPath = v.repoPath
      config.ref = v.ref || 'HEAD'
      config.docsPath = v.docsPath || 'docs'
    } else {
      config.integrationId = v.integrationId
      config.target = v.target
    }
    const input: KnowledgeSyncSourceInput = {
      kbId: kbId ?? v.kbId,
      kind: v.kind,
      name: v.name,
      config,
      enabled: v.enabled,
    }
    if (!editingSync) {
      await commitSync(input)
      return
    }
    Modal.confirm({
      centered: true,
      title: `保存同步源「${editingSync.name}」的修改？`,
      content: '保存后配置立即生效；下次运行将按新配置同步。',
      okText: '保存',
      cancelText: '取消',
      onOk: () => commitSync(input),
    })
  }

  const onRunSync = (s: KnowledgeSyncSource) => {
    Modal.confirm({
      centered: true,
      title: `立即运行同步源「${s.name}」？`,
      content: '将拉取源端最新内容：新增/更新文档会立即入库并重建索引，源端已删除的文档会停用。',
      okText: '运行',
      cancelText: '取消',
      onOk: async () => {
        setRunningSync(s.id)
        try {
          await runSyncSource(s.id)
          message.success(`已提交同步任务：${s.name}`)
          window.setTimeout(() => loadSync(true), 1200)
        } catch (e) {
          showError(e, '提交同步任务失败')
        } finally {
          setRunningSync(null)
        }
      },
    })
  }

  const onDeleteSync = (s: KnowledgeSyncSource) => {
    Modal.confirm({
      centered: true,
      title: `删除同步源「${s.name}」？`,
      content: '已入库文档会保留，只是不再继续同步。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSyncSource(s.id)
          message.success('同步源已删除（已入库文档保留）')
          loadSync()
        } catch (e) {
          showError(e, '删除同步源失败')
        }
      },
    })
  }

  const syncConfigText = (s: KnowledgeSyncSource) =>
    s.kind === 'GIT'
      ? `${cfgStr(s.config, 'repoPath') || '-'} · ${cfgStr(s.config, 'docsPath') || '.'} @ ${cfgStr(s.config, 'ref') || 'HEAD'}`
      : `集成 #${cfgStr(s.config, 'integrationId') || '-'} · ${cfgStr(s.config, 'target') || '-'}`

  const syncStats = (stats: SyncStats) => {
    const items: { label: string; n: number; color: string }[] = [
      { label: '新建', n: stats?.created ?? 0, color: 'green' },
      { label: '更新', n: stats?.updated ?? 0, color: 'blue' },
      { label: '未变', n: stats?.unchanged ?? 0, color: 'default' },
      { label: '停用', n: stats?.deprecated ?? 0, color: 'orange' },
      { label: '失败', n: stats?.failed ?? 0, color: 'red' },
    ]
    const shown = items.filter((x) => x.n > 0)
    return shown.length > 0 ? (
      <Space size={4} wrap>
        {shown.map((x) => (
          <Tag key={x.label} color={x.color}>
            {x.label} {x.n}
          </Tag>
        ))}
      </Space>
    ) : (
      <Typography.Text type="secondary">-</Typography.Text>
    )
  }

  const syncColumns: ColumnsType<KnowledgeSyncSource> = [
    {
      title: '同步源',
      key: 'name',
      width: 220,
      render: (_, r) => (
        <Space direction="vertical" size={2}>
          <Space size={6}>
            <Tag color={r.kind === 'GIT' ? 'geekblue' : 'cyan'}>{r.kind === 'GIT' ? 'Git' : '飞书'}</Tag>
            <span>{r.name}</span>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId) ?? `知识库 #${r.kbId}`}
            {!r.enabled && ' · 已停用'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '配置',
      key: 'config',
      ellipsis: true,
      render: (_, r) => (
        <Tooltip title={JSON.stringify(r.config)}>
          <Typography.Text style={{ fontSize: 12 }} ellipsis>
            {syncConfigText(r)}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 110,
      render: (_, r) => {
        const m = r.lastStatus ? SYNC_STATUS[r.lastStatus] : null
        return m ? <Tag color={m.color}>{m.text}</Tag> : <Tag>未运行</Tag>
      },
    },
    {
      title: '最近结果',
      key: 'stats',
      width: 210,
      render: (_, r) => syncStats(r.lastStats),
    },
    {
      title: '最近同步',
      key: 'lastSyncAt',
      width: 190,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Text style={{ fontSize: 12 }}>{r.lastSyncAt ? fmtTime(r.lastSyncAt) : '-'}</Typography.Text>
          {r.lastError && (
            <Tooltip title={r.lastError}>
              <Typography.Text type="danger" ellipsis style={{ fontSize: 12, maxWidth: 170 }}>
                {r.lastError}
              </Typography.Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 190,
      render: (_, r) => (
        <Space size={2}>
          <Tooltip title={r.enabled ? '立即同步一次' : '同步源已停用'}>
            <Button
              size="small"
              disabled={!r.enabled || r.lastStatus === 'RUNNING'}
              loading={runningSync === r.id}
              onClick={() => onRunSync(r)}
            >
              运行
            </Button>
          </Tooltip>
          <Button size="small" onClick={() => openEditSync(r)}>
            编辑
          </Button>
          <Button size="small" danger onClick={() => onDeleteSync(r)}>
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Button icon={<ReloadOutlined />} onClick={() => loadSync()}>
          刷新
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateSync}>
          新建同步源
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Git 只读服务器本地副本，飞书同步沿用集成凭证；删除只停同步，已入库文档保留。
        </Typography.Text>
      </Space>
      <Table<KnowledgeSyncSource>
        rowKey="id"
        loading={sourcesLoading}
        columns={syncColumns}
        dataSource={sources}
        pagination={false}
        locale={{ emptyText: '暂无同步源。新建后手动运行，或等待后续接入定时同步。' }}
      />

      <Modal
        open={syncModalOpen}
        title={editingSync ? `编辑同步源 · ${editingSync.name}` : '新建同步源'}
        okText="保存"
        cancelText="取消"
        confirmLoading={syncSaving}
        width={640}
        destroyOnHidden
        onOk={onSaveSync}
        onCancel={() => setSyncModalOpen(false)}
      >
        <Form form={syncForm} layout="vertical">
          <Form.Item name="kbId" label="目标知识库" rules={[{ required: true, message: '请选择知识库' }]}>
            <Select
              placeholder="选择入库的知识库"
              disabled={kbId !== undefined}
              options={bases.map((b) => ({ value: b.id, label: b.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="kind" label="同步类型" rules={[{ required: true }]}>
            <Segmented
              options={[
                { value: 'GIT', label: 'Git 本地副本' },
                { value: 'FEISHU', label: '飞书空间/目录' },
              ]}
            />
          </Form.Item>
          <Form.Item name="name" label="名称">
            <Input placeholder={syncKind === 'GIT' ? '如：平台文档仓库' : '如：运维 wiki'} />
          </Form.Item>
          {syncKind === 'GIT' ? (
            <>
              <Form.Item
                name="repoPath"
                label="仓库绝对路径（服务器本地）"
                rules={[{ required: true, message: '请填写仓库绝对路径' }]}
              >
                <Input placeholder="/data/repos/platform-docs" />
              </Form.Item>
              <Space size={12} style={{ display: 'flex' }}>
                <Form.Item name="ref" label="分支/标签/Commit" style={{ flex: 1 }}>
                  <Input placeholder="HEAD" />
                </Form.Item>
                <Form.Item name="docsPath" label="文档子目录" style={{ flex: 1 }}>
                  <Input placeholder="docs" />
                </Form.Item>
              </Space>
            </>
          ) : (
            <>
              <Form.Item
                name="integrationId"
                label="飞书集成 ID"
                rules={[{ required: true, message: '请填写飞书集成 ID' }]}
              >
                <InputNumber min={1} style={{ width: '100%' }} placeholder="集成配置中的 ID" />
              </Form.Item>
              <Form.Item
                name="target"
                label="同步目标"
                rules={[{ required: true, message: '请填写 wiki 空间或云盘文件夹标识' }]}
              >
                <Input placeholder="wiki 空间 ID / 云盘文件夹 token" />
              </Form.Item>
            </>
          )}
          <Form.Item name="docType" label="默认文档类型（识别不出时使用）">
            <Select allowClear placeholder="按目录规则自动识别" options={DOC_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="tags" label="附加标签">
            <Select mode="tags" open={false} placeholder="回车添加标签" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
