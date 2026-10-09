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
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { createRelease, executeRelease, getRelease, listReleases } from '../api'
import type { CreateReleaseInput, ReleaseRecord, ReleaseStatus } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'
import ReleaseDetailDrawer from '../components/ReleaseDetailDrawer'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { workItemColumn } from '../../../shared/components/WorkItemCell'
import WorkItemSelect from '../../../shared/components/WorkItemSelect'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

interface CreateValues {
  buildId?: number
  version?: string
  executor?: string
  agentNodeId?: string
  workItemId?: string
  force?: boolean
}

export default function ReleasesPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <ReleaseCenter id={projectId} />
}

function ReleaseCenter({ id }: { id: string }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const [rows, setRows] = useState<ReleaseRecord[]>([])
  const [detail, setDetail] = useState<ReleaseRecord | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createBusy, setCreateBusy] = useState(false)
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [createForm] = Form.useForm<CreateValues>()

  const load = () => {
    listReleases(id).then(setRows).catch(() => setRows([]))
  }

  useEffect(() => {
    load()
    listAgentNodes().then(setNodes).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  // 深链（需求详情关联记录直达）：/releases?id=<发版id> 直接开管理抽屉，读后清参数
  useEffect(() => {
    const rid = Number(searchParams.get('id'))
    if (!rid) return
    setSearchParams({}, { replace: true })
    getRelease(rid).then(setDetail).catch(() => message.warning(`发版 #${rid} 不存在或已删除`))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  const onCreate = async (v: CreateValues) => {
    setCreateBusy(true)
    try {
      const input: CreateReleaseInput = {
        projectId: id,
        buildId: v.buildId,
        version: v.version,
        executor: v.executor,
        agentNodeId: v.agentNodeId,
        workItemId: v.workItemId,
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
      showError(e, '创建失败')
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
    workItemColumn<ReleaseRecord>(id),
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
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
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
      <FitTable rowKey="id" columns={columns} dataSource={rows} pagination={LIST_PAGINATION}
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
          <Form.Item label="构建 id" name="buildId" rules={[{ required: true, message: '请填写产物来源构建 id' }]}
            extra="产物来源（必填；发版 tag 以该构建 commit 为基准，需求归集按 tag 区间统计）">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="关联工作单元" name="workItemId" extra="可选；选定后发版记录归集到对应需求">
            <WorkItemSelect projectId={id} />
          </Form.Item>
          <Form.Item label="版本" name="version" extra="留空按版本规则自动 +1">
            <Input placeholder="如 1.0.1" />
          </Form.Item>
          <Form.Item label="执行方式" name="executor" extra="缺省取发版配置">
            <Select
              allowClear
              placeholder="取配置"
              options={[{ value: 'LOCAL', label: 'LOCAL（本机）' }, { value: 'AGENT', label: 'AGENT（Agent 节点）' }]}
            />
          </Form.Item>
          <Form.Item label="执行节点" name="agentNodeId" extra="AGENT 时可显式指定；留空 = 发版配置节点 → 路由链">
            <Select
              allowClear
              placeholder="选择 runner 节点"
              options={nodes.map((n) => ({
                value: String(n.id),
                label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
              }))}
            />
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
