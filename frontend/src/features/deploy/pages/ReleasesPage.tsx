// 发版页（/releases）：当前项目的发版操作与历史（CAP-11）。
// 新建发版收 extra 主操作（Modal 表单，创建即执行并开详情 Drawer）；行内「管理」开 Drawer
// 看 WS 实时日志并执行/回滚/删除。发版配置（Nexus/模板/版本规则）在项目设置「发版配置」Tab 维护。
import {
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { createRelease, executeRelease, listReleases } from '../api'
import type { CreateReleaseInput, ReleaseRecord, ReleaseStatus } from '../types'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'
import ReleaseDetailDrawer from '../components/ReleaseDetailDrawer'

interface CreateValues {
  buildId?: number
  version?: string
  executor?: string
  serverId?: number
  force?: boolean
}

export default function ReleasesPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <ReleaseCenter id={projectId} />
}

function ReleaseCenter({ id }: { id: string }) {
  const [rows, setRows] = useState<ReleaseRecord[]>([])
  const [detail, setDetail] = useState<ReleaseRecord | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createBusy, setCreateBusy] = useState(false)
  const [createForm] = Form.useForm<CreateValues>()

  const load = () => {
    listReleases(id).then(setRows).catch(() => setRows([]))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const onCreate = async (v: CreateValues) => {
    setCreateBusy(true)
    try {
      const input: CreateReleaseInput = {
        projectId: id,
        buildId: v.buildId,
        version: v.version,
        executor: v.executor,
        serverId: v.serverId,
        force: v.force,
      }
      const r = await createRelease(input)
      const running = await executeRelease(r.id)
      setCreateOpen(false)
      createForm.resetFields()
      setDetail(running)
      message.success(`发版 v${r.version} 已开始执行`)
      load()
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    } finally {
      setCreateBusy(false)
    }
  }

  const columns: ColumnsType<ReleaseRecord> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '版本', dataIndex: 'version', width: 120,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: ReleaseStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    { title: '执行方式', dataIndex: 'executor', width: 90, render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: 'tag', dataIndex: 'tagName', width: 120, render: (v: string) => v || '-' },
    { title: 'Nexus 引用', dataIndex: 'nexusRef', width: 160, ellipsis: true, render: (v: string) => v || '-' },
    { title: '构建', dataIndex: 'buildId', width: 70, render: (v: number) => (v ? `#${v}` : '-') },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 170,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '操作', key: 'ops', width: 90,
      render: (_: unknown, r) => (
        <Button size="small" onClick={() => setDetail(r)}>管理</Button>
      ),
    },
  ]

  return (
    <Card
      title="发版"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建发版
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        发版执行器（CAP-11）：把构建制品推送 Nexus 并打版本 tag；点「管理」开 Drawer 看实时日志并执行/回滚。发版配置在项目设置「发版配置」Tab 维护。
      </Typography.Paragraph>
      <Table rowKey="id" columns={columns} dataSource={rows} pagination={false}
        locale={{
          emptyText: '暂无发版记录。先在项目设置保存发版配置，再点「新建发版」创建并执行第一个发版。',
        }}
        scroll={{ x: 1000 }} />

      <Modal
        title="新建发版"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => createForm.submit()}
        okText="创建并执行"
        confirmLoading={createBusy}
        width={480}
      >
        <Form form={createForm} layout="vertical" onFinish={onCreate}>
          <Form.Item label="构建 id" name="buildId" extra="产物来源（可选，留空则模板自带制品）">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="版本" name="version" extra="留空按版本规则自动 +1">
            <Input placeholder="如 1.0.1" />
          </Form.Item>
          <Form.Item label="执行方式" name="executor" extra="缺省取发版配置">
            <Select
              allowClear
              placeholder="取配置"
              options={[{ value: 'LOCAL', label: 'LOCAL' }, { value: 'REMOTE', label: 'REMOTE' }]}
            />
          </Form.Item>
          <Form.Item label="服务器 id" name="serverId" extra="REMOTE 时必填">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="force" valuePropName="checked">
            <Checkbox>force（允许同版本重发）</Checkbox>
          </Form.Item>
        </Form>
      </Modal>

      <ReleaseDetailDrawer record={detail} onClose={() => setDetail(null)} onChanged={load} />
    </Card>
  )
}
