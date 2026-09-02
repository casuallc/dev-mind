// CAP-19 项目「Jira 同步」配置卡（后台项目设置 Tab）：
// 配置 CRUD + 启用开关 + 上次同步状态 + 立即同步；同步把 Jira issue 拉成 DRAFT 需求（单向只拉取）。
import { useCallback, useEffect, useState } from 'react'
import {
  AutoComplete,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CloudDownloadOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import {
  createJiraSyncConfig,
  deleteJiraSyncConfig,
  listExternalProjects,
  listIntegrations,
  listJiraSyncConfigs,
  runJiraSync,
  updateJiraSyncConfig,
} from '../api'
import type { ExternalProject, Integration, JiraSyncConfig, JiraSyncConfigInput } from '../types'
import { fmtTime } from '../../../shared/utils/format'

interface Props {
  projectId: string
}

export default function JiraSyncTab({ projectId }: Props) {
  const [configs, setConfigs] = useState<JiraSyncConfig[]>([])
  const [jiraIntegrations, setJiraIntegrations] = useState<Integration[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<JiraSyncConfig | null>(null)
  const [saving, setSaving] = useState(false)
  const [runningId, setRunningId] = useState<number | null>(null)
  const [extProjects, setExtProjects] = useState<ExternalProject[]>([])
  const [form] = Form.useForm<JiraSyncConfigInput>()
  const watchIntegrationId = Form.useWatch('integrationId', form)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [cfgs, integrations] = await Promise.all([
        listJiraSyncConfigs(projectId),
        listIntegrations().catch(() => [] as Integration[]),
      ])
      setConfigs(cfgs)
      setJiraIntegrations(integrations.filter((i) => i.type === 'JIRA'))
    } catch (e) {
      message.error(`加载 Jira 同步配置失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    reload()
  }, [reload])

  // 选中集成后可拉取其可见的 Jira 项目列表辅助选择
  useEffect(() => {
    if (!editOpen || !watchIntegrationId) {
      setExtProjects([])
      return
    }
    listExternalProjects(watchIntegrationId)
      .then(setExtProjects)
      .catch(() => setExtProjects([]))
  }, [editOpen, watchIntegrationId])

  useEffect(() => {
    if (!editOpen) return
    form.setFieldsValue(
      editing
        ? {
            integrationId: editing.integrationId,
            jiraProjectKey: editing.jiraProjectKey,
            jql: editing.jql ?? '',
            enabled: editing.enabled,
            pollIntervalSec: editing.pollIntervalSec,
            firstSyncDays: editing.firstSyncDays,
          }
        : {
            integrationId: undefined,
            jiraProjectKey: undefined,
            jql: '',
            enabled: true,
            pollIntervalSec: 300,
            firstSyncDays: 7,
          },
    )
  }, [editOpen, editing, form])

  const onSave = async (values: JiraSyncConfigInput) => {
    setSaving(true)
    try {
      if (editing) {
        await updateJiraSyncConfig(projectId, editing.id, values)
        message.success('已更新')
      } else {
        await createJiraSyncConfig(projectId, values)
        message.success('已创建，点「立即同步」拉取第一批 issue')
      }
      setEditOpen(false)
      reload()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const onRun = async (row: JiraSyncConfig) => {
    setRunningId(row.id)
    try {
      const r = await runJiraSync(projectId, row.id)
      if (r.error) {
        message.error(`同步失败：${r.error}`)
      } else if (r.imported + r.updated + r.skipped === 0) {
        message.info('同步完成：无新 issue')
      } else {
        message.success(`同步完成：新增 ${r.imported} 条需求，刷新 ${r.updated} 条，跳过 ${r.skipped} 条`)
      }
      reload()
    } catch (e) {
      message.error(`同步失败：${(e as Error).message}`)
    } finally {
      setRunningId(null)
    }
  }

  const onToggleEnabled = async (row: JiraSyncConfig, enabled: boolean) => {
    try {
      await updateJiraSyncConfig(projectId, row.id, { enabled })
      message.success(enabled ? '已启用轮询' : '已暂停轮询')
      reload()
    } catch (e) {
      message.error(`操作失败：${(e as Error).message}`)
    }
  }

  const onDelete = async (row: JiraSyncConfig) => {
    try {
      await deleteJiraSyncConfig(projectId, row.id)
      message.success('已删除（已导入的需求与链接保留）')
      reload()
    } catch (e) {
      message.error(`删除失败：${(e as Error).message}`)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space wrap>
        <Button
          size="small"
          type="primary"
          icon={<PlusOutlined />}
          disabled={jiraIntegrations.length === 0}
          onClick={() => {
            setEditing(null)
            setEditOpen(true)
          }}
        >
          新建同步配置
        </Button>
        <Button size="small" icon={<ReloadOutlined />} onClick={reload} />
        {jiraIntegrations.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            请先在「平台集成」中新建 JIRA 类型集成
          </Typography.Text>
        )}
      </Space>

      <Table<JiraSyncConfig>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={configs}
        pagination={false}
        locale={{ emptyText: '暂无 Jira 同步配置' }}
        columns={[
          {
            title: 'Jira 项目',
            dataIndex: 'jiraProjectKey',
            width: 110,
            render: (k: string) => <Tag color="blue">{k}</Tag>,
          },
          { title: '集成实例', dataIndex: 'integrationName', width: 120, render: (n) => n ?? '-' },
          {
            title: '附加 JQL',
            dataIndex: 'jql',
            ellipsis: true,
            render: (j?: string | null) => j ?? <Typography.Text type="secondary">（全部 issue）</Typography.Text>,
          },
          {
            title: '轮询',
            key: 'poll',
            width: 130,
            render: (_, row) => (
              <Space size={4}>
                <Switch size="small" checked={row.enabled} onChange={(v) => onToggleEnabled(row, v)} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {row.enabled ? `${row.pollIntervalSec}s` : '已暂停'}
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: '上次同步',
            key: 'last',
            width: 240,
            render: (_, row) =>
              row.lastError ? (
                <Tooltip title={row.lastError}>
                  <Tag color="red">失败</Tag>
                </Tooltip>
              ) : row.lastSyncAt ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {fmtTime(row.lastSyncAt)} · 新增 {row.lastImported ?? 0} / 刷新{' '}
                  {row.lastUpdatedCount ?? 0}
                </Typography.Text>
              ) : (
                <Typography.Text type="secondary">未同步</Typography.Text>
              ),
          },
          {
            title: '操作',
            width: 200,
            render: (_, row) => (
              <Space size={4}>
                <Button
                  size="small"
                  type="primary"
                  ghost
                  icon={<CloudDownloadOutlined />}
                  loading={runningId === row.id}
                  onClick={() => onRun(row)}
                >
                  立即同步
                </Button>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setEditing(row)
                    setEditOpen(true)
                  }}
                />
                <Popconfirm title="删除该同步配置？" description="已导入的需求与 Jira 链接保留" onConfirm={() => onDelete(row)}>
                  <Button size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Drawer
        title={editing ? `编辑 Jira 同步「${editing.jiraProjectKey}」` : '新建 Jira 同步'}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={560}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setEditOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={() => form.submit()}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item
            label="Jira 集成实例"
            name="integrationId"
            rules={[{ required: true, message: '请选择集成实例' }]}
            extra="在「平台集成」中维护（类型 JIRA）"
          >
            <Select
              disabled={!!editing}
              placeholder="选择 Jira 实例"
              options={jiraIntegrations.map((i) => ({ value: i.id, label: `${i.name}（${i.baseUrl}）` }))}
            />
          </Form.Item>
          <Form.Item
            label="Jira 项目"
            name="jiraProjectKey"
            rules={[{ required: true, message: '请选择或输入 Jira 项目 key' }]}
            extra="选中集成后自动列出可见项目；也可直接输入 key（如 PROJ）。换项目会清零水位线重新全量拉取"
          >
            <AutoComplete
              placeholder="选择或输入项目 key"
              options={extProjects.map((p) => ({ value: p.key, label: `${p.key} ${p.name ?? ''}` }))}
              filterOption={(input, option) =>
                (option?.value ?? '').toUpperCase().includes(input.toUpperCase())
              }
            />
          </Form.Item>
          <Form.Item
            label="附加 JQL 过滤"
            name="jql"
            extra="可选，与 project 条件 AND 组合。如 issuetype in (Story, Bug) AND labels = ai"
          >
            <Input placeholder="issuetype in (Story, Bug)" />
          </Form.Item>
          <Form.Item label="轮询间隔（秒）" name="pollIntervalSec" extra="最小 60，默认 300">
            <InputNumber min={60} max={86400} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item
            label="首次同步范围（天）"
            name="firstSyncDays"
            extra="首轮只拉近 N 天内有更新的 issue，防老项目全量灌入；0 = 不限（慎用）。已有水印后此值不再生效"
          >
            <InputNumber min={0} max={3650} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item label="启用轮询" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Drawer>
    </Space>
  )
}
