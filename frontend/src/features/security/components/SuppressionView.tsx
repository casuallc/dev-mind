// 抑制管理（FR-07/FR-08）：内层 Segmented 切「规则抑制 / CVE 抑制」。
// 规则抑制由发现详情创建，这里只列与删；CVE 抑制支持建（平台级仅 ADMIN）。
import { Button, Checkbox, DatePicker, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import {
  createCveSuppression,
  deleteCveSuppression,
  deleteSuppression,
  listCveSuppressions,
  listSuppressions,
} from '../api'
import type { CveSuppression, Suppression } from '../types'
import FitTable, { noWrapColumns } from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { isAdmin } from '../../auth/authStore'
import { usePermissions } from '../../auth/usePermissions'
import { ENGINE_LABEL } from './common'

const TYPE_LABEL: Record<string, string> = {
  FALSE_POSITIVE: '误报',
  ACCEPTED_RISK: '风险接受',
  THIRD_PARTY: '第三方组件',
}

interface Props {
  projectId: string
  refreshTick: number
}

export default function SuppressionView({ projectId, refreshTick }: Props) {
  const { canAction } = usePermissions()
  const canSuppress = canAction('project.security.action.suppress')
  const [tab, setTab] = useState<string>('rules')

  return (
    <div>
      <Segmented
        style={{ marginBottom: 12 }}
        value={tab}
        onChange={(v) => setTab(v as string)}
        options={[
          { value: 'rules', label: '规则抑制' },
          { value: 'cve', label: 'CVE 抑制' },
        ]}
      />
      {tab === 'rules' ? (
        <RuleTable projectId={projectId} refreshTick={refreshTick} canSuppress={canSuppress} />
      ) : (
        <CveTable projectId={projectId} refreshTick={refreshTick} canSuppress={canSuppress} />
      )}
    </div>
  )
}

function RuleTable({ projectId, refreshTick, canSuppress }: { projectId: string; refreshTick: number; canSuppress: boolean }) {
  const [rows, setRows] = useState<Suppression[]>([])
  const [loading, setLoading] = useState(false)

  const refresh = () => {
    setLoading(true)
    listSuppressions(projectId)
      .then(setRows)
      .catch(() => {})
      .finally(() => setLoading(false))
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(refresh, [projectId, refreshTick])

  const onDelete = async (id: number) => {
    try {
      await deleteSuppression(id)
      message.success('已删除')
      refresh()
    } catch (e) {
      showError(e)
    }
  }

  return (
    <FitTable<Suppression>
      rowKey="id"
      loading={loading}
      dataSource={rows}
      pagination={LIST_PAGINATION}
      columns={noWrapColumns<Suppression>([
        { title: '引擎', dataIndex: 'engine', width: 110, render: (v: string) => ENGINE_LABEL[v] ?? v },
        {
          title: '指纹',
          dataIndex: 'fingerprint',
          ellipsis: true,
          render: (v: string) => (
            <Typography.Text copyable={{ text: v }} style={{ fontFamily: 'monospace' }}>
              {v.slice(0, 16)}…
            </Typography.Text>
          ),
        },
        { title: '类型', dataIndex: 'type', width: 110, render: (v: string) => <Tag>{TYPE_LABEL[v] ?? v}</Tag> },
        { title: '理由', dataIndex: 'reason', ellipsis: true },
        { title: '过期时间', dataIndex: 'expiresAt', width: 160, render: (v: string | null) => (v ? fmtTime(v) : '长期') },
        { title: '创建人', dataIndex: 'createdBy', width: 110 },
        { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (v: string) => fmtTime(v) },
        ...(canSuppress
          ? [
              {
                title: '操作',
                key: 'op',
                width: 80,
                render: (_: unknown, r: Suppression) => (
                  <Popconfirm title="删除该抑制？相关发现将在下次扫描复现" onConfirm={() => onDelete(r.id)}>
                    <Button type="link" size="small" danger>
                      删除
                    </Button>
                  </Popconfirm>
                ),
              },
            ]
          : []),
      ])}
    />
  )
}

function CveTable({ projectId, refreshTick, canSuppress }: { projectId: string; refreshTick: number; canSuppress: boolean }) {
  const [rows, setRows] = useState<CveSuppression[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const [submitting, setSubmitting] = useState(false)

  const refresh = () => {
    setLoading(true)
    listCveSuppressions(projectId)
      .then(setRows)
      .catch(() => {})
      .finally(() => setLoading(false))
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(refresh, [projectId, refreshTick])

  const onDelete = async (id: number) => {
    try {
      await deleteCveSuppression(id)
      message.success('已删除')
      refresh()
    } catch (e) {
      showError(e)
    }
  }

  const onCreate = async () => {
    const v = form.getFieldsValue(true)
    if (!v.groupId?.trim() || !v.artifactId?.trim() || !v.cve?.trim() || !v.reason?.trim()) {
      message.warning('group/artifact/CVE/理由均为必填')
      return
    }
    if (v.type === 'ACCEPTED_RISK' && !v.expiresAt) {
      message.warning('风险接受必须设置过期时间')
      return
    }
    setSubmitting(true)
    try {
      await createCveSuppression({
        groupId: v.groupId.trim(),
        artifactId: v.artifactId.trim(),
        cve: v.cve.trim().toUpperCase(),
        projectId: v.platform ? undefined : projectId,
        type: v.type,
        reason: v.reason.trim(),
        expiresAt: v.expiresAt ? dayjs(v.expiresAt).format('YYYY-MM-DD HH:mm:ss') : undefined,
      })
      message.success('已创建 CVE 抑制')
      setOpen(false)
      form.resetFields()
      refresh()
    } catch (e) {
      showError(e)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      {canSuppress && (
        <Button type="primary" icon={<PlusOutlined />} style={{ marginBottom: 12 }} onClick={() => setOpen(true)}>
          新建 CVE 抑制
        </Button>
      )}
      <FitTable<CveSuppression>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        columns={noWrapColumns<CveSuppression>([
          { title: 'CVE', dataIndex: 'cve', width: 160 },
          { title: '构件', width: 260, ellipsis: true, render: (_, r) => `${r.groupId}:${r.artifactId}` },
          {
            title: '级别',
            key: 'scope',
            width: 90,
            render: (_, r) => (r.projectId ? <Tag>项目级</Tag> : <Tag color="purple">平台级</Tag>),
          },
          { title: '类型', dataIndex: 'type', width: 110, render: (v: string) => <Tag>{TYPE_LABEL[v] ?? v}</Tag> },
          { title: '理由', dataIndex: 'reason', ellipsis: true },
          { title: '过期时间', dataIndex: 'expiresAt', width: 160, render: (v: string | null) => (v ? fmtTime(v) : '长期') },
          { title: '创建人', dataIndex: 'createdBy', width: 110 },
          ...(canSuppress
            ? [
                {
                  title: '操作',
                  key: 'op',
                  width: 80,
                  render: (_: unknown, r: CveSuppression) => (
                    <Popconfirm title="删除该 CVE 抑制？" onConfirm={() => onDelete(r.id)}>
                      <Button type="link" size="small" danger>
                        删除
                      </Button>
                    </Popconfirm>
                  ),
                },
              ]
            : []),
        ])}
      />
      <Modal
        title="新建 CVE 抑制"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={onCreate}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" initialValues={{ type: 'FALSE_POSITIVE', platform: false }} preserve={false}>
          {isAdmin() && (
            <Form.Item name="platform" valuePropName="checked">
              <Checkbox>平台级（对全部项目生效，仅管理员）</Checkbox>
            </Form.Item>
          )}
          <Space.Compact block>
            <Form.Item name="groupId" label="groupId（* 通配）" rules={[{ required: true, message: '必填' }]} style={{ width: '50%' }}>
              <Input placeholder="com.example 或 *" />
            </Form.Item>
            <Form.Item name="artifactId" label="artifactId（* 通配）" rules={[{ required: true, message: '必填' }]} style={{ width: '50%' }}>
              <Input placeholder="fastjson 或 *" />
            </Form.Item>
          </Space.Compact>
          <Form.Item name="cve" label="CVE 编号" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="CVE-2022-25845" />
          </Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'FALSE_POSITIVE', label: '误报' },
                { value: 'ACCEPTED_RISK', label: '风险接受（必须填过期时间）' },
              ]}
            />
          </Form.Item>
          <Form.Item name="reason" label="理由" rules={[{ required: true, message: '必填' }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="expiresAt" label="过期时间（风险接受必填）">
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
