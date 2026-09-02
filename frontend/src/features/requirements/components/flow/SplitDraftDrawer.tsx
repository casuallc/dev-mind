// 拆分草稿 Drawer（CAP-14 FR-06/07）：读取 AI 拆分的 wi-plan.json 草稿，
// 人编辑（类型/标题/spec/依赖）后确认固化为正式 Work Item + depends_on 边。
import { useCallback, useEffect, useState } from 'react'
import { Button, Drawer, Empty, Input, Popconfirm, Select, Space, Spin, Table, Tag, Typography, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { confirmSplit, getSplitDraft } from '../../api'
import type { SplitDraftItem, WorkItemType } from '../../types'

const WI_TYPES: WorkItemType[] = ['DESIGN', 'DEVELOPMENT', 'TEST', 'DOCUMENT', 'REVIEW']

interface Row extends SplitDraftItem {
  key: number
}

export default function SplitDraftDrawer({ projectId, requirementId, open, onClose, onConfirmed }: {
  projectId: string
  requirementId: string
  open: boolean
  onClose: () => void
  onConfirmed: () => void
}) {
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [sessionId, setSessionId] = useState<string | undefined>()
  const [rows, setRows] = useState<Row[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const draft = await getSplitDraft(projectId, requirementId)
      setSessionId(draft.sessionId)
      setRows(draft.items.map((it, i) => ({ ...it, key: i, dependsOn: it.dependsOn ?? [] })))
    } catch (e) {
      message.error(`读取拆分草稿失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId, requirementId])

  useEffect(() => {
    if (open) {
      load()
    }
  }, [open, load])

  const patch = (key: number, p: Partial<Row>) =>
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...p } : r)))

  const columns: ColumnsType<Row> = [
    { title: '#', width: 40, render: (_, __, i) => i },
    {
      title: '类型', dataIndex: 'type', width: 130,
      render: (t: WorkItemType, r) => (
        <Select
          size="small"
          value={t}
          style={{ width: 118 }}
          options={WI_TYPES.map((x) => ({ value: x, label: x }))}
          onChange={(v) => patch(r.key, { type: v })}
        />
      ),
    },
    {
      title: '标题', dataIndex: 'title', width: 220,
      render: (v: string, r) => (
        <Input size="small" value={v} onChange={(e) => patch(r.key, { title: e.target.value })} />
      ),
    },
    {
      title: '执行说明 spec', dataIndex: 'spec',
      render: (v: string, r) => (
        <Input.TextArea
          size="small"
          autoSize={{ minRows: 1, maxRows: 5 }}
          value={v}
          onChange={(e) => patch(r.key, { spec: e.target.value })}
        />
      ),
    },
    {
      title: '依赖(#下标)', dataIndex: 'dependsOn', width: 120,
      render: (deps: number[], r) => (
        <Input
          size="small"
          placeholder="如 0,2"
          value={deps.join(',')}
          onChange={(e) => {
            const ds = e.target.value.split(',').map((s) => s.trim()).filter(Boolean)
              .map(Number).filter((n) => !Number.isNaN(n))
            patch(r.key, { dependsOn: ds })
          }}
        />
      ),
    },
    {
      title: '', key: 'ops', width: 56,
      render: (_, r) => (
        <Button size="small" type="link" danger onClick={() => setRows((rs) => rs.filter((x) => x.key !== r.key))}>
          删除
        </Button>
      ),
    },
  ]

  const onConfirm = async () => {
    if (rows.length === 0) {
      message.warning('清单为空，无需固化')
      return
    }
    setSaving(true)
    try {
      await confirmSplit(projectId, requirementId, rows.map(({ type, title, spec, dependsOn }) => ({
        type, title, spec, dependsOn,
      })))
      message.success(`已固化 ${rows.length} 个工作单元`)
      onClose()
      onConfirmed()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      title={
        <Space size={8}>
          <span>拆分草稿</span>
          {sessionId && <Typography.Text code style={{ fontSize: 12 }}>会话 {sessionId}</Typography.Text>}
        </Space>
      }
      width={920}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Button size="small" onClick={load}>重新读取</Button>
          <Popconfirm title="固化为正式工作单元？" description="将批量创建并建立 depends_on 依赖边" onConfirm={onConfirm}>
            <Button size="small" type="primary" loading={saving} disabled={rows.length === 0}>
              确认固化（{rows.length}）
            </Button>
          </Popconfirm>
        </Space>
      }
    >
      {loading ? (
        <Spin />
      ) : rows.length === 0 ? (
        <Empty description="暂无草稿：先点击「AI 拆分工作单元」，待会话完成通知后再来查看；也可直接在「工作单元」Tab 手工新建" />
      ) : (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            依赖列填本清单内下标（0 起，多个用逗号分隔）；确认前可任意编辑/增删。
          </Typography.Text>
          <Table rowKey="key" size="small" columns={columns} dataSource={rows} pagination={false} />
          <Button
            size="small"
            icon={<PlusOutlined />}
            onClick={() => setRows((rs) => [...rs, {
              key: rs.length ? Math.max(...rs.map((r) => r.key)) + 1 : 0,
              type: 'DEVELOPMENT', title: '', spec: '', dependsOn: [],
            }])}
          >
            加一行
          </Button>
          <Tag color="gold" style={{ alignSelf: 'flex-start' }}>
            固化前需求状态不会变化；固化后按工作单元进度自动推进
          </Tag>
        </Space>
      )}
    </Drawer>
  )
}
