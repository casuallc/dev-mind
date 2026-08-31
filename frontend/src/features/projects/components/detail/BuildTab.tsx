// 构建配置 Tab：有序构建步骤（上移/下移/增删改）。
import { useState } from 'react'
import {
  Button,
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
import type { ColumnsType } from 'antd/es/table'
import { ArrowDownOutlined, ArrowUpOutlined, PlusOutlined } from '@ant-design/icons'
import {
  addBuildStep,
  deleteBuildStep,
  listBuildSteps,
  reorderBuildSteps,
  updateBuildStep,
} from '../../api'
import type { BuildStep, BuildStepInput } from '../../types'

const LOCATION_OPTIONS = ['LOCAL', 'REMOTE']

export default function BuildTab({ id, steps, onChanged }: {
  id: string
  steps: BuildStep[]
  onChanged: (s: BuildStep[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<BuildStep | null>(null)
  const [form] = Form.useForm()

  const openEdit = (s: BuildStep | null) => {
    setEditing(s)
    form.setFieldsValue(
      s ?? { name: '', command: '', workingDir: '', location: 'LOCAL', sortOrder: steps.length },
    )
    setOpen(true)
  }

  const onSave = async (v: BuildStepInput) => {
    try {
      if (editing) {
        await updateBuildStep(id, editing.id, v)
      } else {
        await addBuildStep(id, v)
      }
      setOpen(false)
      onChanged(await listBuildSteps(id))
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const move = async (index: number, dir: -1 | 1) => {
    const next = [...steps]
    const target = index + dir
    if (target < 0 || target >= next.length) return
    const [it] = next.splice(index, 1)
    next.splice(target, 0, it)
    const ordered = next.map((s, i) => ({ ...s, sortOrder: i }))
    onChanged(await reorderBuildSteps(id, ordered))
  }

  const confirmDelete = (s: BuildStep) => {
    Modal.confirm({
      centered: true,
      title: '删除构建步骤？',
      content: `「${s.command}」`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteBuildStep(id, s.id)
        onChanged(await listBuildSteps(id))
        message.success('已删除')
      },
    })
  }

  const columns: ColumnsType<BuildStep> = [
    { title: '顺序', dataIndex: 'sortOrder', width: 70 },
    { title: '名称', dataIndex: 'name', width: 140, render: (n?: string) => n || '-' },
    { title: '命令', dataIndex: 'command', render: (c: string) => <code style={{ fontSize: 12 }}>{c}</code> },
    { title: '目录', dataIndex: 'workingDir', width: 120, render: (d?: string) => d || '-' },
    { title: '位置', dataIndex: 'location', width: 90, render: (l: string) => <Tag color={l === 'REMOTE' ? 'purple' : 'default'}>{l}</Tag> },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, r, idx) => (
        <Space size={4}>
          <Button size="small" icon={<ArrowUpOutlined />} disabled={idx === 0} onClick={() => move(idx, -1)} />
          <Button size="small" icon={<ArrowDownOutlined />} disabled={idx === steps.length - 1} onClick={() => move(idx, 1)} />
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
          添加步骤
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          有序构建步骤，按顺序执行；位置可选本机（LOCAL）或远程服务器（REMOTE，委托 CAP-08）。
        </Typography.Text>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={steps} pagination={false} />
      <Modal title={editing ? '编辑构建步骤' : '添加构建步骤'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name">
            <Input placeholder="如 编译打包" />
          </Form.Item>
          <Form.Item label="命令" name="command" rules={[{ required: true, message: '请输入命令' }]}>
            <Input.TextArea rows={2} placeholder="如 mvn -q package -DskipTests" />
          </Form.Item>
          <Form.Item label="执行目录（相对仓库根，留空=根）" name="workingDir">
            <Input placeholder="如 app/" />
          </Form.Item>
          <Form.Item label="执行位置" name="location">
            <Select options={LOCATION_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <InputNumber min={0} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
